import Foundation
import SwiftUI

/// 页面路由。等待页与结果页由 NavigationStack 承载。
enum Route: Hashable {
    case loading
    case result
}

/// 起卦流程协调器：点击起卦后先保存历史；
/// 有提问时进入等待页，跑完本地/云端解读再进结果页一次性呈现完整结果；无提问则直接进结果页展示四维通用解读。
@MainActor
final class CastFlow: ObservableObject {
    @Published var path = NavigationPath()
    @Published var result: DivinationResult?
    @Published var question = ""
    @Published var reply = ""
    @Published var mode = ""
    @Published var error = ""
    @Published var hint = ""
    @Published var isGenerating = false

    private var started = false

    func start(_ raw: DivinationResult, question rawQuestion: String) {
        let q = rawQuestion.trimmingCharacters(in: .whitespacesAndNewlines)
        let adjusted = Divination.adjustByQuestion(raw, question: q)

        result = adjusted
        question = q
        reply = ""
        mode = ""
        error = ""
        hint = ""
        started = false

        HistoryStore.save(
            question: q,
            method: adjusted.method,
            original: adjusted.original.name,
            changed: adjusted.changed.name,
            movingLine: adjusted.movingLine,
            judgment: adjusted.original.judgment
        )

        if q.isEmpty {
            path.append(Route.result)
        } else {
            path.append(Route.loading)
        }
    }

    func runAiIfNeeded() {
        guard !started, let r = result else { return }
        started = true
        isGenerating = true

        let yao = YaoDb.yao(r.original.name, r.movingLine).yao
        let settings = AppSettings.shared

        if settings.cloudOn {
            mode = "大师解卦"
            let (system, user) = AiClient.promptOf(
                question: question, original: r.original, changed: r.changed,
                movingLine: r.movingLine, yaoText: yao
            )
            Task {
                defer { isGenerating = false }
                do {
                    reply = try await AiClient.request(config: settings.config, system: system, user: user)
                    error = ""
                } catch {
                    reply = ""
                    error = "大师解卦失败：\(error.localizedDescription)"
                }
                finish()
            }
        } else {
            mode = "解卦"
            hint = (settings.config.cloudEnabled && !settings.hasKey) ? "未配置 API Key，已改用本地模型" : ""
            let (system, user) = LocalAiClient.promptOf(
                question: question, original: r.original, changed: r.changed,
                movingLine: r.movingLine, yaoText: yao
            )
            Task {
                defer { isGenerating = false }
                do {
                    reply = try await LocalAiClient.generate(system: system, user: user)
                    error = ""
                } catch {
                    reply = ""
                    error = "解卦失败：\(error.localizedDescription)"
                }
                finish()
            }
        }
    }

    private func finish() {
        // 用结果页替换等待页，返回时直接回到首页。
        path.removeLast()
        path.append(Route.result)
    }
}