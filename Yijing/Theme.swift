import SwiftUI

/// 应用配色与字体（与 Android 端 colors.xml 保持一致）。
enum Theme {

    static let brand = Color(hex: 0x8A6D3B)
    static let brandDark = Color(hex: 0x6E552E)
    static let ink = Color(hex: 0x201D17)
    static let inkMuted = Color(hex: 0x837A69)
    static let paper = Color(hex: 0xFBF8F2)
    static let divider = Color(hex: 0xE4DED1)
    static let cinnabar = Color(hex: 0xA63A2B)
    static let cinnabarDark = Color(hex: 0x7E2B20)
    static let gold = Color(hex: 0xC8A45C)
    static let readCard = Color(hex: 0xFCF3EE)

    static let cardBg = Color.white

    /// 印章 / 古体氛围的衬线字体（宋体类）。iOS 内置衬线中文回退。
    static var sealFont: Font { .system(size: 17, weight: .regular, design: .serif) }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: 1
        )
    }
}

/// 结果卡片：白底、圆角、细描边（对应 bg_card.xml）。
struct CardBackground: ViewModifier {
    var fill: Color = Theme.cardBg
    var border: Color = Theme.divider
    var radius: CGFloat = 10
    func body(content: Content) -> some View {
        content
            .background(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .fill(fill)
                    .overlay(
                        RoundedRectangle(cornerRadius: radius, style: .continuous)
                            .strokeBorder(border, lineWidth: 1)
                    )
            )
    }
}

extension View {
    func cardStyle(fill: Color = Theme.cardBg, border: Color = Theme.divider, radius: CGFloat = 10) -> some View {
        modifier(CardBackground(fill: fill, border: border, radius: radius))
    }
}