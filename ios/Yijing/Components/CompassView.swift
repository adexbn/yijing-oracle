import SwiftUI

/// 结果罗盘：外圈后天八卦（符号 + 卦名）、中圈二十四山（天干+地支+四隅卦），
/// 中心显示本卦六爻（动爻金色高亮），外沿小三角指针指向本卦上卦方位。
struct CompassView: View {
    let original: Hexagram
    let changed: Hexagram
    let movingLine: Int

    // 后天八卦顺时针排布（自正北「坎」起）
    private let ringTrigrams: [Trigram] = [.kan, .gen, .zhen, .xun, .li, .kun, .dui, .qian]

    // 二十四山（自正北「子」起顺时针每 15°）
    private let shan24 = [
        "子", "癸", "丑", "艮", "寅", "甲", "卯", "乙", "辰", "巽", "巳", "丙",
        "午", "丁", "未", "坤", "申", "庚", "酉", "辛", "戌", "乾", "亥", "壬"
    ]

    private let dirAngle: [String: Double] = [
        "北": 0, "东北": 45, "东": 90, "东南": 135,
        "南": 180, "西南": 225, "西": 270, "西北": 315
    ]

    var body: some View {
        Canvas { context, size in
            let r = min(size.width, size.height) / 2 - 12
            let cx = size.width / 2
            let cy = size.height / 2
            drawRing(context: &context, cx: cx, cy: cy, r: r)
            drawShan24(context: &context, cx: cx, cy: cy, r: r)
            drawBaguaRing(context: &context, cx: cx, cy: cy, r: r)
            drawCenter(context: &context, cx: cx, cy: cy, r: r)
        }
        .aspectRatio(1, contentMode: .fit)
    }

    private func drawRing(context: inout GraphicsContext, cx: CGFloat, cy: CGFloat, r: CGFloat) {
        circle(cx: cx, cy: cy, r: r, stroke: Theme.brand, width: 1.5, context: &context)
        circle(cx: cx, cy: cy, r: r * 0.66, stroke: Theme.divider, width: 1, context: &context)
        circle(cx: cx, cy: cy, r: r * 0.52, stroke: Theme.divider, width: 1, context: &context)
        circle(cx: cx, cy: cy, r: r * 0.44, stroke: Theme.brand, width: 1.5, context: &context)
        // 上/下（阳/阴）半分界
        drawLine(from: CGPoint(x: cx - r, y: cy), to: CGPoint(x: cx + r, y: cy), color: Theme.divider, width: 1, context: &context)
    }

    private func drawShan24(context: inout GraphicsContext, cx: CGFloat, cy: CGFloat, r: CGFloat) {
        let rr = r * 0.56
        for (i, s) in shan24.enumerated() {
            let a = rad(-90.0 + Double(i) * 15.0)
            let p = CGPoint(x: cx + rr * CGFloat(cos(a)), y: cy + rr * CGFloat(sin(a)))
            text(s, at: p, size: 13, color: Theme.inkMuted, context: &context)
        }
    }

    private func drawBaguaRing(context: inout GraphicsContext, cx: CGFloat, cy: CGFloat, r: CGFloat) {
        let upper = original.upper
        let lower = original.lower
        for (i, tg) in ringTrigrams.enumerated() {
            let hot = tg == upper || tg == lower
            let a = rad(-90.0 + Double(i) * 45.0)
            let sx = cx + r * 0.88 * CGFloat(cos(a))
            let sy = cy + r * 0.88 * CGFloat(sin(a))
            text(tg.symbol, at: CGPoint(x: sx, y: sy), size: 18, color: hot ? Theme.gold : Theme.ink, context: &context)
            let nx = cx + r * 0.72 * CGFloat(cos(a))
            let ny = cy + r * 0.72 * CGFloat(sin(a))
            text(tg.label, at: CGPoint(x: nx, y: ny), size: 14, color: hot ? Theme.brand : Theme.inkMuted, context: &context)
        }
    }

    private func drawCenter(context: inout GraphicsContext, cx: CGFloat, cy: CGFloat, r: CGFloat) {
        let rc = r * 0.44
        let lineW = rc * 0.92
        let gap: CGFloat = 7
        let startY = cy - gap * 2.5
        let upperLines = original.upper.lines
        let lowerLines = original.lower.lines

        for i in 0..<6 {
            let yang = i < 3 ? ((lowerLines >> i) & 1) == 1 : ((upperLines >> (i - 3)) & 1) == 1
            let color = (i + 1 == movingLine) ? Theme.gold : Theme.ink
            let y = startY + CGFloat(i) * gap
            drawYao(cx: cx, y: y, w: lineW, yang: yang, color: color, context: &context)
        }

        let deg = dirAngle[original.upper.direction] ?? 0
        drawNeedle(cx: cx, cy: cy, r: r, deg: deg, context: &context)
    }

    private func drawNeedle(cx: CGFloat, cy: CGFloat, r: CGFloat, deg: Double, context: inout GraphicsContext) {
        // 外圈小三角游标：尖端朝外，落在符号环与卦名环之间，不与环内文字重叠。
        let a = rad(-90.0 + deg)
        let dx = CGFloat(cos(a)), dy = CGFloat(sin(a))
        let tipR = r * 0.90
        let tip = CGPoint(x: cx + tipR * dx, y: cy + tipR * dy)
        let baseR = r * 0.98
        let half: CGFloat = 5
        let px = -dy, py = dx
        let b1 = CGPoint(x: cx + baseR * dx + px * half, y: cy + baseR * dy + py * half)
        let b2 = CGPoint(x: cx + baseR * dx - px * half, y: cy + baseR * dy - py * half)

        var path = Path()
        path.move(to: tip)
        path.addLine(to: b1)
        path.addLine(to: b2)
        path.closeSubpath()
        context.fill(path, with: .color(Theme.gold))
    }

    private func drawYao(cx: CGFloat, y: CGFloat, w: CGFloat, yang: Bool, color: Color, context: inout GraphicsContext) {
        if yang {
            drawLine(from: CGPoint(x: cx - w / 2, y: y), to: CGPoint(x: cx + w / 2, y: y), color: color, width: 2.5, context: &context)
        } else {
            let g = w * 0.18
            drawLine(from: CGPoint(x: cx - w / 2, y: y), to: CGPoint(x: cx - g, y: y), color: color, width: 2.5, context: &context)
            drawLine(from: CGPoint(x: cx + g, y: y), to: CGPoint(x: cx + w / 2, y: y), color: color, width: 2.5, context: &context)
        }
    }

    // ---------- 绘图基元 ----------

    private func circle(cx: CGFloat, cy: CGFloat, r: CGFloat, stroke: Color, width: CGFloat, context: inout GraphicsContext) {
        let rect = CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)
        context.stroke(Path(ellipseIn: rect), with: .color(stroke), lineWidth: width)
    }

    private func drawLine(from: CGPoint, to: CGPoint, color: Color, width: CGFloat, context: inout GraphicsContext) {
        var path = Path()
        path.move(to: from)
        path.addLine(to: to)
        context.stroke(path, with: .color(color), lineWidth: width)
    }

    private func text(_ s: String, at p: CGPoint, size: CGFloat, color: Color, context: inout GraphicsContext) {
        context.draw(
            Text(s).font(.system(size: size)).foregroundColor(color),
            at: p
        )
    }

    private func rad(_ deg: Double) -> Double { deg * Double.pi / 180.0 }
}