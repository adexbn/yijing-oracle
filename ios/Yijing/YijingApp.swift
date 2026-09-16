import Foundation
import SwiftUI

@main
struct YijingApp: App {
    init() {
        // 记录本次启动的版本/构建号，方便确认安装的是哪个包。
        YjLog.startSession()
        // 后台预热推理后端：llama_backend_init() 会加载并编译 Metal 着色器库，
        // 真机实测约 15 秒。放在启动时预热，用户点「解卦」时就不用再白等这 15 秒。
        // 用 .utility 优先级并延后 2 秒，避开首屏渲染，免得启动动画发卡。
        // 模型还没下载时（首次启动会走初始化页）跳过，避免白跑一次失败加载。
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 2) {
            guard ModelManager.isDownloaded() else { return }
            LlamaCPP.warmUp()
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(AppSettings.shared)
                .preferredColorScheme(.light)
        }
    }
}
