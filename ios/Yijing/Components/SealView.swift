import SwiftUI

/// 圆形印章主按钮：白底、朱红双圈、篆书式正文「起卦」，副字「今·時·空」。
struct SealView: View {
    var text: String = "起卦"
    var subText: String = "今·時·空"

    var body: some View {
        ZStack {
            Circle().fill(Color.white)
            Circle().strokeBorder(Theme.cinnabar, lineWidth: 3).padding(2)
            Circle().strokeBorder(Theme.cinnabar, lineWidth: 1.2).padding(9)
            VStack(spacing: 0) {
                Text(text)
                    .font(.system(size: 30, weight: .regular, design: .serif))
                    .foregroundColor(Theme.cinnabar)
                Text(subText)
                    .font(.system(size: 9, weight: .regular, design: .serif))
                    .foregroundColor(Theme.cinnabar)
                    .padding(.top, 6)
            }
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

/// 按下时整体缩放的按钮样式，模拟印章盖章手感。
struct SealButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .opacity(configuration.isPressed ? 0.9 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}