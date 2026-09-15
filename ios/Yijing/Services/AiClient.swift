import Foundation

/// AI 联网解读：OpenAI 兼容的 chat/completions 协议（DeepSeek/OpenAI/通义/GLM）。
enum AiClient {

    struct ApiError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// 构造用于解读的爻辞上下文提示词。
    static func promptOf(
        question: String,
        original: Hexagram,
        changed: Hexagram,
        movingLine: Int,
        yaoText: String
    ) -> (system: String, user: String) {
        let system = "你是精通《周易》的解卦师，结合卦象与用户所问给出简明、中肯的解读，不超过 180 字，用中文。"
        let user = """
        所问：\(question)
        本卦：第\(original.number)卦 \(original.name)（\(original.symbol)）
        变卦：第\(changed.number)卦 \(changed.name)（\(changed.symbol)）
        动爻：第 \(movingLine) 爻
        动爻爻辞：\(yaoText)
        本卦卦辞：\(original.judgment)
        """
        return (system, user)
    }

    /// 调用 chat/completions，返回正文。失败抛带 HTTP 状态的异常。
    static func request(config: AiConfig, system: String, user: String) async throws -> String {
        var base = config.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        if base.hasSuffix("/") { base = String(base.dropLast()) }
        guard let url = URL(string: base + "/chat/completions") else {
            throw ApiError(message: "Base URL 无效")
        }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 60
        req.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body: [String: Any] = [
            "model": config.model,
            "messages": [
                ["role": "system", "content": system],
                ["role": "user", "content": user]
            ],
            "temperature": 0.7
        ]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse else {
            throw ApiError(message: "网络响应异常")
        }
        guard (200...299).contains(http.statusCode) else {
            let text = String(data: data, encoding: .utf8) ?? ""
            throw ApiError(message: "HTTP \(http.statusCode) \(text.prefix(300))")
        }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = json["choices"] as? [[String: Any]],
              let first = choices.first,
              let message = first["message"] as? [String: Any],
              let content = message["content"] as? String else {
            throw ApiError(message: "响应解析失败")
        }
        return content
    }
}