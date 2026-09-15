import SwiftUI

@main
struct YijingApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(AppSettings.shared)
                .preferredColorScheme(.light)
        }
    }
}