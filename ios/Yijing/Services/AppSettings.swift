import Foundation
import Combine

/// 云端 AI 配置（与 Android AiClient.Config 对齐）。
struct AiConfig {
    var provider: String
    var baseUrl: String
    var apiKey: String
    var model: String
    var cloudEnabled: Bool

    static let `default` = AiConfig(
        provider: "DeepSeek",
        baseUrl: "https://api.deepseek.com",
        apiKey: "",
        model: "deepseek-chat",
        cloudEnabled: false
    )
}

final class AppSettings: ObservableObject {

    @Published var config: AiConfig {
        didSet { persist(config) }
    }

    static let shared = AppSettings()

    /// 服务商默认值（名称 -> (BaseURL, 默认模型)）。
    static let providers: [(name: String, baseUrl: String, model: String)] = [
        ("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
        ("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        ("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        ("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        ("自定义", "", "")
    ]

    private static let keyProvider = "yijing.provider"
    private static let keyBaseUrl = "yijing.baseUrl"
    private static let keyModel = "yijing.model"
    private static let keyCloudEnabled = "yijing.cloudEnabled"
    private static let keychainApiKey = "yijingApiKey"

    private init() {
        let d = UserDefaults.standard
        let provider = d.string(forKey: Self.keyProvider) ?? AiConfig.default.provider
        let (defUrl, defModel) = Self.defaults(for: provider)
        config = AiConfig(
            provider: provider,
            baseUrl: d.string(forKey: Self.keyBaseUrl) ?? defUrl,
            apiKey: KeychainHelper.read(key: Self.keychainApiKey),
            model: d.string(forKey: Self.keyModel) ?? defModel,
            cloudEnabled: d.bool(forKey: Self.keyCloudEnabled)
        )
    }

    private func persist(_ c: AiConfig) {
        let d = UserDefaults.standard
        d.set(c.provider, forKey: Self.keyProvider)
        d.set(c.baseUrl, forKey: Self.keyBaseUrl)
        d.set(c.model, forKey: Self.keyModel)
        d.set(c.cloudEnabled, forKey: Self.keyCloudEnabled)
        KeychainHelper.save(key: Self.keychainApiKey, value: c.apiKey)
    }

    static func defaults(for provider: String) -> (String, String) {
        guard let p = providers.first(where: { $0.name == provider }) else { return ("", "") }
        return (p.baseUrl, p.model)
    }

    var hasKey: Bool { !config.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    var cloudOn: Bool { config.cloudEnabled && hasKey }
}