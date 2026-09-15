import Foundation

/// 本地小模型解读：调用 llama.cpp 跑 Qwen3-1.7B GGUF，全程离线。
enum LocalAiClient {

    /// 定稿系统提示词：直白、有对象感、建议具体。
    static let SYSTEM = "你是一个会解卦的朋友，说话直白、接地气，像跟人当面聊天，千万不要文绉绉、不要用文言字眼。" +
        "按这个顺序用大白话讲：1. 先说他抽到的是什么卦，这个卦本身代表什么状态、什么性子（用生活里的话讲）。" +
        "2. 再说动的那一爻在提醒什么（把爻辞翻译成大白话，讲它对人有什么实际意思）。" +
        "3. 再说变成的那个卦，点明事情会往哪个方向走。" +
        "4. 最后紧扣他问的具体问题，给几句实实在在的建议，包括该怎么做、要注意和避免什么。" +
        "建议要具体到眼下能做的事，别给「积累经验」这类泛泛的话，而且建议的方向要和前面的结论一致，不要自相矛盾。" +
        "全程用「你」称呼问卦的人，控制在150字以内，像聊天一样自然。"

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

    /// 使用本地模型生成解读。模型未下载时抛出带提示的异常。
    static func generate(system: String, user: String) async throws -> String {
        guard ModelManager.isDownloaded() else {
            throw ModelManager.ApiError("本地模型尚未下载，请先到「设置」页下载模型（约 1.2GB）")
        }
        let path = ModelManager.modelFileURL().path
        // 推理耗时，放到后台线程执行，避免阻塞 UI。
        return try await withCheckedThrowingContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let raw = try LlamaCPP.complete(modelPath: path, system: system, user: user)
                    cont.resume(returning: stripThinking(raw))
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
    }

    /// 去掉 Qwen3 的思考过程标签及内容，只保留最终回答。
    static func stripThinking(_ raw: String) -> String {
        var s = raw
        if let r = s.range(of: #"(?is)<\s*think\s*>.*?</\s*think\s*>"#, options: .regularExpression) {
            s.removeSubrange(r)
        }
        if let open = s.range(of: #"(?is)<\s*think\s*>"#, options: .regularExpression) {
            s = String(s[..<open.lowerBound])
        }
        s = s.replacingOccurrences(of: #"(?is)<\s*/?\s*response\s*>"#, with: "", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}