import SwiftUI

/// 起卦等待页：卦象已在点击时算出，本地/云端解读较慢，先在此等待，跑完再进结果页一次呈现完整结果。
struct LoadingView: View {
    @EnvironmentObject private var flow: CastFlow

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            BrushWritingView()
                .frame(width: 140, height: 140)
            Text("正在解卦…")
                .font(.system(size: 14))
                .foregroundColor(Theme.inkMuted)
                .padding(.top, 24)
            Text("卦象已定，正在推演爻辞与解读")
                .font(.system(size: 12))
                .foregroundColor(Theme.inkMuted.opacity(0.75))
                .padding(.top, 8)
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.paper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
        .onAppear {
            flow.runAiIfNeeded()
        }
    }
}