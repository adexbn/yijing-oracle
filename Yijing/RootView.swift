import SwiftUI

struct RootView: View {
    @StateObject private var flow = CastFlow()

    var body: some View {
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
    }
}