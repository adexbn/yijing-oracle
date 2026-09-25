import Foundation

/// 本地小模型解读：调用 llama.cpp 跑 Qwen3.5-2B GGUF，全程离线。
enum LocalAiClient {

    /// 定稿系统提示词：E 方案（精简 + few-shot 短样例）。
    ///
    /// 与 Android 端 `LocalAiClient.kt` 的 `SYSTEM` 逐字同步，改这里就同步改那边。
    static let SYSTEM =
        "你是解卦的朋友，说话直白、接地气，简短有力，控制在 100 字内。" +
        "先讲卦的性子，再讲动爻提醒什么，最后说变卦和建议。" +
        "只输出解读，别废话。\n\n" +
        "例：问「今年换工作好不好」，抽到乾卦 2 爻动→同人。\n" +
        "答：你现在势头挺足，但别急着跳，第二爻提醒你先稳住本事、把东西学扎实再动。变卦同人，说明真跳了能找到志同道合的团队，但得是你先有料才行。建议再熬俩月，把手头项目做漂亮了再投。"

    static func promptOf(
        question: String,
        original: Hexagram,
        changed: Hexagram,
        movingLine: Int,
        yaoText: String
    ) -> (system: String, user: String) {
        let user = AiClient.promptOf(
            question: question, original: original, changed: changed,
            movingLine: movingLine, yaoText: yaoText
        ).user
        return (SYSTEM, user)
    }

    /// 单次生成上限。non-thinking 模式不需要思考段预算，给 200 足够（答案约 100 字）。
    /// 与 Android 端 `MAX_TOKENS_THINKING` 取齐。
    private static let maxTokensThinking: Int32 = 200

    /// 兜底重跑时的上限。non-thinking 模式下思考段不会吃预算，
    /// 兜底主要防极端长输出，给 400 够了。
    private static let maxTokensRetry: Int32 = 400

    /// 使用本地模型生成解读。模型未下载或不完整时抛出带提示的异常。
    static func generate(system: String, user: String) async throws -> String {
        YjLog.log("LocalAiClient.generate 开始")
        guard let size = ModelManager.modelFileSize() else {
            YjLog.log("generate 失败：模型尚未下载")
            throw ModelManager.ApiError("本地模型尚未下载，请先到「设置」页下载模型（约 1.2GB）")
        }
        guard size > ModelManager.minModelBytes else {
            YjLog.log("generate 失败：模型文件不完整 size=\(size)")
            throw ModelManager.ApiError("本地模型文件不完整（当前 \(ModelManager.formatSize(size))，应约 1.2GB），请到「设置」页重新下载或导入")
        }
        let path = ModelManager.modelFileURL().path
        YjLog.log("generate: modelPath=\(path) size=\(size)")
        // 推理耗时，放到后台线程执行，避免阻塞 UI。
        return try await withCheckedThrowingContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    YjLog.log("开始调用 LlamaCPP.complete（maxTokens=\(maxTokensThinking)）")
                    let first = try LlamaCPP.complete(
                        modelPath: path, system: system, user: user, maxTokens: maxTokensThinking
                    )
                    logThinkingOf(first)
                    var text = stripThinking(first)
                    YjLog.log("后处理：原始 \(first.utf8.count) 字节 → 去思考标签后 \(text.utf8.count) 字节")

                    // 兜底：non-thinking 模式下若输出为空，可能是采样退化或模板问题，
                    // 加大预算重跑一次，宁可多等一轮也不给空白。
                    if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        YjLog.log("空结果兜底：首次输出为空，预算提到 \(maxTokensRetry) 重跑一次")
                        let second = try LlamaCPP.complete(
                            modelPath: path, system: system, user: user, maxTokens: maxTokensRetry
                        )
                        logThinkingOf(second)
                        text = stripThinking(second)
                        YjLog.log("空结果兜底：重跑后得到 \(text.utf8.count) 字节")
                    }
                    cont.resume(returning: text)
                } catch {
                    YjLog.log("LlamaCPP.complete 抛出错误: \(error)")
                    cont.resume(throwing: error)
                }
            }
        }
    }

    /// 量一下思考块里**到底有没有内容**，再记日志。
    ///
    /// 只检查有没有 `<think>` 标签是假阳性：模板补了空思考块时模型照样会吐一个空壳
    /// `<think></think>`，那不叫在思考（这正是 1.2.0 日志里「看着像思考、其实是纯答案」的原因）。
    /// 所以这里量的是标签之间的字符数，未闭合的块也算 —— 被 token 上限砍断的思考段就是这种。
    private static func logThinkingOf(_ raw: String) {
        guard let re = try? NSRegularExpression(
            pattern: "(?is)<\\s*think(?:ing)?\\s*>(.*?)(</\\s*think(?:ing)?\\s*>|$)"
        ) else { return }
        let full = NSRange(raw.startIndex..<raw.endIndex, in: raw)
        let chars = re.matches(in: raw, range: full).reduce(0) { acc, m -> Int in
            guard m.numberOfRanges > 1, let r = Range(m.range(at: 1), in: raw) else { return acc }
            return acc + String(raw[r]).trimmingCharacters(in: .whitespacesAndNewlines).count
        }
        YjLog.log("思考段：\(chars) 字" + (chars > 0 ? "（保留思考是硬约束）" : "（无思考内容，需检查模板是否漏配）"))
    }

    /// 系统提示词里那些「要求」的指纹词。
    ///
    /// 模型偶尔会把自己的指令当正文复述出来 —— 真机截图里答案末尾就整句贴了
    /// 「（字数控制在150字以内，像聊天一样自然）」。见 `dropInstructionEcho`。
    ///
    /// 只收「正常解卦绝不会出现」的词：像「该怎么做」这种正文里真会出现的说法一律不收，
    /// 免得把好端端的解读尾巴切掉。
    private static let instructionEchoSeeds = [
        "字数控制", "字以内", "百来字", "像聊天一样", "聊天一样自然",
        "文绉绉", "积累经验", "自相矛盾", "用「你」称呼", "只输出解读", "不要复述"
    ]

    /// 「回音」单句的长度上限：比这更长就不像复述要求了，宁可不剥。
    private static let echoMaxChars = 60

    /// 正则替换的薄封装：pattern 写死在本文件里，编译失败就当没匹配直接返回原文。
    private static func replacing(_ s: String, pattern: String, with template: String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return s }
        let full = NSRange(s.startIndex..<s.endIndex, in: s)
        return re.stringByReplacingMatches(in: s, range: full, withTemplate: template)
    }

    /// 剥掉贴在末尾的「系统提示词回音」。
    ///
    /// 只从**末尾**逐句剥，剥到第一句不像回音的句子就停：复述要求一定出现在结尾，
    /// 从尾巴上动手不会误伤正文。
    private static func dropInstructionEcho(_ input: String) -> String {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        // 按句切开，并且把句间的空白/换行一起留在本句尾部 —— 直接丢掉会毁掉正文的段落。
        guard let re = try? NSRegularExpression(pattern: "\\s*[^。！？!?\\n]+[。！？!?]\\s*|\\s*[^。！？!?\\n]+") else {
            return trimmed
        }
        let full = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
        var parts = re.matches(in: trimmed, range: full).compactMap { m -> String? in
            guard let r = Range(m.range, in: trimmed) else { return nil }
            return String(trimmed[r])
        }
        while parts.count > 1 {
            let last = parts[parts.count - 1]
            if last.count <= echoMaxChars && instructionEchoSeeds.contains(where: { last.contains($0) }) {
                parts.removeLast()
            } else {
                break
            }
        }
        let joined = parts.joined()
        // 整段输出就是一句回音（模型一个字正文都没写）：返回空串，交给上层重试。
        if joined.count <= echoMaxChars && instructionEchoSeeds.contains(where: { joined.contains($0) }) {
            return ""
        }
        return joined
    }

    /// 去掉 Qwen3 的思考过程标签及内容，只保留最终回答。
    ///
    /// 真机反馈过「思考过程漏到答案里」，所以这里的清洗比早期版本彻底得多。
    /// 另外 MNN 官方只在**提示词缓存**里做同类清理（`prompt_cache_utils.hpp` 的
    /// `stripThinkBlocks`，只被 `llm.cpp` 的 `updateCachedPromptText` / `syncPromptCache` 调用），
    /// `response()` 吐出来的生成文本它一概不管 —— 生成侧的兜底只能我们自己扛。
    ///
    /// 处理顺序（Android 端 `LocalAiClient.stripThinking` 必须逐条一致）：
    /// 1. 循环删掉成对的思考块（`<think>` / `<thinking>` 混写也认）；
    /// 2. 删掉落单的 `</think>` 闭合标签（模板已含 `<think>` 时，模型只会补一个闭合标签）；
    /// 3. 还有没闭合的开标签，就从那里截断（被 token 上限砍断的思考段）；
    /// 4. 清掉残留的对话模板标记，以及纯文本界面里只会显示成星号的 Markdown 加粗；
    /// 5. 剥掉尾巴上复述系统要求的回音（`dropInstructionEcho`）。
    static func stripThinking(_ raw: String) -> String {
        var s = raw

        // 1) 成对思考块：循环删。早期实现只删第一个，模型吐两段就漏一段。
        let pair = "(?is)<\\s*think(?:ing)?\\s*>.*?</\\s*think(?:ing)?\\s*>"
        while true {
            let next = replacing(s, pattern: pair, with: "")
            if next == s { break }
            s = next
        }

        // 2) 落单的闭合标签：删标签本身，不要当成截断点（否则会把后面的正文一起丢掉）。
        s = replacing(s, pattern: "(?is)</\\s*think(?:ing)?\\s*>", with: "")

        // 3) 未闭合的开标签：思考段被截断，从这里全砍。
        if let open = s.range(of: "(?is)<\\s*think(?:ing)?\\s*>", options: .regularExpression) {
            s = String(s[..<open.lowerBound])
        }

        // 4) 对话模板标记（response 包裹、Qwen 的 <|im_start|> 等）+ Markdown 加粗星号。
        s = replacing(
            s,
            pattern: "(?is)<\\s*\\|?\\s*/?\\s*(response|assistant|im_start|im_end|endoftext|im_sep)\\s*\\|?\\s*>",
            with: ""
        )
        // 上面的标签清掉后，模板里的 `<|im_start|>assistant\n` 会剩一个裸露的角色词在开头。
        s = replacing(s, pattern: "(?is)^\\s*(?:assistant|user|system)\\s*\\n", with: "")
        s = s.replacingOccurrences(of: "**", with: "")

        // 5) 末尾的系统提示词回音。
        return dropInstructionEcho(s).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}