import SwiftUI

/// 毛笔运墨写字动画：逐笔写出「爻」字，写完收笔后循环。用于解卦等待时的占位，无需图片资源。
struct BrushWritingView: View {
    private let duration: Double = 2.6

    private let ink = Color(red: 0x2B / 255, green: 0x2A / 255, blue: 0x28 / 255)
    private let rod = Color(red: 0xA8 / 255, green: 0x3A / 255, blue: 0x22 / 255)
    private let grid = Color(red: 0x2B / 255, green: 0x2A / 255, blue: 0x28 / 255).opacity(22 / 255)

    // 「爻」字四笔（相对字形半高 s 的坐标），顺序：上赳撇/捺、下赳撇/捺
    private let strokes: [(CGFloat, CGFloat, CGFloat, CGFloat)] = [
        (0.34, -0.82, -0.34, -0.18),
        (-0.34, -0.82, 0.34, -0.18),
        (0.34, 0.18, -0.34, 0.82),
        (-0.34, 0.18, 0.34, 0.82)
    ]

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { timeline in
            let phase = timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: duration) / duration
            Canvas { context, size in
                draw(size: size, phase: phase, context: &context)
            }
        }
    }

    private func draw(size: CGSize, phase: Double, context: inout GraphicsContext) {
        let w = size.width, h = size.height
        guard w > 0, h > 0 else { return }
        let cx = w / 2, cy = h / 2
        let s = h * 0.19

        // 淡米字格
        let g = h * 0.34
        let rect = CGRect(x: cx - g, y: cy - g, width: g * 2, height: g * 2)
        context.stroke(Path(rect), with: .color(grid), lineWidth: h * 0.012)

        // 书写进度（末尾留 20% 停顿收笔）
        let hold = 0.2
        let perf = min(1.0, phase / (1.0 - hold))
        let seg = perf * Double(strokes.count)
        let idx = Int(seg)
        let t = min(1.0, max(0.0, seg - Double(idx)))

        // 墨迹
        var brushPos: CGPoint? = nil
        for (j, st) in strokes.enumerated() {
            let sx = cx + st.0 * s, sy = cy + st.1 * s
            let ex = cx + st.2 * s, ey = cy + st.3 * s
            switch true {
            case j < idx:
                line(PathLine(start: (sx, sy), end: (ex, ey)), ink, h * 0.045, context: &context)
            case j == idx:
                let px = sx + (ex - sx) * CGFloat(t)
                let py = sy + (ey - sy) * CGFloat(t)
                line(PathLine(start: (sx, sy), end: (px, py)), ink, h * 0.045, context: &context)
                brushPos = CGPoint(x: px, y: py)
            default:
                break
            }
        }

        // 毛笔（写完最后一笔则收笔隐去）
        if idx < strokes.count, let pos = brushPos {
            drawBrush(at: pos, h: h, context: &context)
        }
    }

    private func line(_ line: PathLine, _ color: Color, _ width: CGFloat, context: inout GraphicsContext) {
        var path = Path()
        path.move(to: line.start)
        path.addLine(to: line.end)
        context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }

    private func drawBrush(at p: CGPoint, h: CGFloat, context: inout GraphicsContext) {
        let bl = h * 0.10
        let bw = h * 0.030
        let sl = h * 0.20
        let rw = bw * 0.42

        var tip = Path()
        tip.move(to: p)
        tip.addLine(to: CGPoint(x: p.x - bw, y: p.y - bl))
        tip.addLine(to: CGPoint(x: p.x + bw, y: p.y - bl))
        tip.closeSubpath()
        context.fill(tip, with: .color(ink))

        let rodRect = CGRect(x: p.x - rw, y: p.y - bl - sl, width: rw * 2, height: sl)
        context.fill(Path(rodRect), with: .color(rod))
    }
}

private struct PathLine {
    let start: CGPoint
    let end: CGPoint
    init(start: (CGFloat, CGFloat), end: (CGFloat, CGFloat)) {
        self.start = CGPoint(x: start.0, y: start.1)
        self.end = CGPoint(x: end.0, y: end.1)
    }
}