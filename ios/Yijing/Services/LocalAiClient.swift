import Foundation

/// 本地小模型解读：调用 llama.cpp 跑 Qwen3-1.7B GGUF，全程离线。
enum LocalAiClient {

    /// 定稿系统提示词：直白、有对象感、建议具体。
    ///
    /// 2026-09-25 改过一轮：原来写的是「按这个顺序用大白话讲：1. … 2. … 3. … 4. ……，
    /// 控制在150字以内，像聊天一样自然」，真机上模型会把这些**要求本身**当正文吐出来 ——
    /// 答案照抄了 1./2./3./4. 的编号，末尾还逐字贴上「（字数控制在150字以内，像聊天一样自然）」
    /// （见 `stripThinking` 第 5 步）。所以这里把可被照抄的「编号清单 + 括号里的硬指标」
    /// 改成没有编号的自然叙述，并在最后一句显式禁止复述要求。
    ///
    /// Android 端 `LocalAiClient.kt` 的 `SYSTEM` 必须与本串**逐字相同**，改这里就同步改那边。
    static let SYSTEM = "你是一个会解卦的朋友，说话直白、接地气，像跟人当面聊天，不要文绉绉、不要用文言字眼。" +
        "顺着讲三件事：先说他抽到的卦本身是什么状态、什么性子，用生活里的话讲；" +
        "再说动的那一爻在提醒什么，把爻辞翻成大白话，讲对他实际意味着什么；" +
        "最后说变成的卦，点明事情会往哪个方向走。" +
        "结尾紧扣他问的那件事给几句实在建议，包括该怎么做、要注意和避免什么，" +
        "要具体到眼下能做的事，别给「积累经验」这类泛泛的话，方向也别和前面的结论打架。" +
        "全程用「你」称呼他，长度控制在百来字，像聊天一样自然。" +
        "只输出解读本身，不要复述、解释或提到上面这些要求。"

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

    /// 单次生成上限。思考段和正式回答**共用**同一份 token 预算，所以按「最坏情况」给：
    /// 真机基准里预算太小的时候，思考段就把钱花光了、去标签后一个字不剩。
    /// 与 Android 端 `MAX_TOKENS_THINKING` 取齐；仍受 `n_ctx=2048` 约束（提示词约 333 token）。
    private static let maxTokensThinking: Int32 = 768

    /// 兜底重跑时的上限。真出现上面那种情况时，模型其实还没开始写答案就被截断了 ——
    /// 与其给用户一张空白解读，不如把预算加大再跑一轮，宁可多等一轮。
    /// 333（提示词）+ 1536 = 1869 < 2048，仍在上下文窗口内。
    private static let maxTokensRetry: Int32 = 1536

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

                    // 兜底：思考段把预算吃光时，答案一个字都没来得及写，去标签后就是空白。
                    // 不能靠「关思考」绕（关思考是硬约束禁止的），改成加大预算重跑。
                    if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        YjLog.log("空结果兜底：思考段吃光 \(maxTokensThinking) token、答案被截断，预算提到 \(maxTokensRetry) 重跑一次")
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