import SwiftUI

/// 六爻竖排展示：上爻（第6爻）到初爻（第1爻）自上而下，动爻用朱红底 +「动爻」徽标高亮。
struct HexagramLinesView: View {
    let upper: Trigram
    let lower: Trigram
    let movingLine: Int

    var body: some View {
        VStack(spacing: 3) {
            ForEach((1...6).reversed(), id: \.self) { line in
                row(line)
            }
        }
    }

    @ViewBuilder
    private func row(_ line: Int) -> some View {
        let yang = isYang(line)
        let isMoving = line == movingLine

        HStack(spacing: 0) {
            Text(labelOf(line, yang: yang))
                .font(.system(size: 11))
                .foregroundColor(Theme.inkMuted)
                .frame(width: 42, alignment: .trailing)

            YaoBar(yang: yang, color: isMoving ? Theme.cinnabar : Theme.ink)
                .frame(height: 3)

            if isMoving {
                Text("动爻")
                    .font(.system(size: 9))
                    .foregroundColor(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 3)
                    .background(Capsule().fill(Theme.cinnabar))
                    .padding(.leading, 8)
            }
        }
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.trailing, isMoving ? 0 : 60)
        .background(
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .fill(Theme.cinnabar.opacity(isMoving ? 0.10 : 0))
        )
    }

    private func isYang(_ line: Int) -> Bool {
        let lines = line <= 3 ? lower.lines : upper.lines
        let bit = line <= 3 ? line - 1 : line - 4
        return (lines & (1 << bit)) != 0
    }

    private func labelOf(_ line: Int, yang: Bool) -> String {
        let y = yang ? "九" : "六"
        let pos = ["初", "二", "三", "四", "五", "上"][line - 1]
        return (line == 1 || line == 6) ? pos + y : y + pos
    }
}

/// 单条爻线：阳爻为实线，阴爻为中间断开的双短线。
private struct YaoBar: View {
    let yang: Bool
    let color: Color

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            if yang {
                Capsule().fill(color).frame(width: w, height: h)
            } else {
                let seg = (w - 18) / 2
                HStack(spacing: 0) {
                    Capsule().fill(color).frame(width: seg, height: h)
                    Spacer().frame(width: 18)
                    Capsule().fill(color).frame(width: seg, height: h)
                }.frame(width: w, height: h)
            }
        }
    }
}