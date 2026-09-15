import SwiftUI

@main
struct YijingApp: App {
    init() {
        // 记录本次启动的版本/构建号，方便确认安装的是哪个包。
        YjLog.startSession()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(AppSettings.shared)
                .preferredColorScheme(.light)
        }
    }
}