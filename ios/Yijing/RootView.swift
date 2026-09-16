import SwiftUI

struct RootView: View {
    @StateObject private var flow = CastFlow()

    /// 本地模型是否就绪。未就绪时先走「首次初始化」，避免用户进来点起卦才发现没模型。
    @State private var modelReady: Bool = ModelManager.isDownloaded()

    var body: some View {
        ZStack {
            if modelReady {
                NavigationStack(path: $flow.path) {
                    HomeView()
                        .navigationDestination(for: Route.self) { route in
                            switch route {
                            case .loading: LoadingView()
                            case .result: ResultView()
                            }
                        }
                }
                .environmentObject(flow)
                .tint(Theme.brand)
                .transition(.opacity)
            } else {
                OnboardingView {
                    withAnimation(.easeInOut(duration: 0.4)) { modelReady = true }
                }
                .transition(.opacity)
            }
        }
    }
}
