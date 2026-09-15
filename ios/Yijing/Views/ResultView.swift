import SwiftUI

struct ResultView: View {
    @EnvironmentObject private var flow: CastFlow
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Group {
            if let r = flow.result {
                content(r)
            } else {
                EmptyView()
            }
        }
        .background(Theme.paper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden(true)
    }

    private func content(_ r: DivinationResult) -> some View {
        VStack(spacing: 0) {
            header(r)
            ScrollView {
                VStack(spacing: 12) {
                    CompassView(original: r.original, changed: r.changed, movingLine: r.movingLine)
                        .frame(width: 320, height: 320)
                        .frame(maxWidth: .infinity)

                    originalCard(r)

                    changedCard(r)

                    if !flow.question.isEmpty {
                        questionCard
                    }

                    if flow.question.isEmpty {
                        genericCard(r)
                    } else {
                        aiCard
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 28)
            }
        }
    }

    // MARK: - 顶栏

    private func header(_ r: DivinationResult) -> some View {
        HStack(alignment: .top) {
            Button { dismiss() } label: {
                Text("‹ 返回").font(.system(size: 15)).foregroundColor(Theme.brand).padding(8)
            }
            .buttonStyle(.plain)

            Spacer()

            VStack(spacing: 2) {
                Text(r.original.name)
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(Theme.ink)
                Text("变卦 \(r.changed.name) · 动爻第 \(r.movingLine) 爻")
                    .font(.system(size: 12))
                    .foregroundColor(Theme.inkMuted)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            Color.clear.frame(width: 60, height: 1)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 4)
    }

    // MARK: - 本卦

    private func originalCard(_ r: DivinationResult) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionTitle("本卦（现状）")

            HStack {
                Text("第\(r.original.number)卦 · \(r.original.name)（\(r.original.alias)）")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(Theme.ink)
                Spacer()
                Text(String(r.original.unicodeSymbol))
                    .font(.system(size: 24))
                    .foregroundColor(Theme.brand)
            }
            .padding(.top, 6)

            HexagramLinesView(upper: r.original.upper, lower: r.original.lower, movingLine: r.movingLine)
                .padding(.top, 10)

            labeled("象义", XiangYi.of(r.original.number), bodyColor: Theme.inkMuted)
                .padding(.top, 10)
            labeled("卦辞", r.original.judgment, bodyColor: Theme.ink)
                .padding(.top, 8)
            labeled("白话", GuaciBaihua.of(r.original.number), bodyColor: Theme.inkMuted)
                .padding(.top, 6)

            // 动爻
            sectionTitle("动爻（转折）")
                .padding(.top, 16)

            Text("第\(r.movingLine)爻")
                .font(.system(size: 14))
                .foregroundColor(Theme.brandDark)
                .padding(.top, 6)

            let yao = YaoDb.yao(r.original.name, r.movingLine)
            labeled("爻辞", yao.yao, bodyColor: Theme.ink)
                .padding(.top, 4)
            labeled("白话", yao.baihua, bodyColor: Theme.inkMuted)
                .padding(.top, 6)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
    }

    // MARK: - 变卦

    private func changedCard(_ r: DivinationResult) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionTitle("变卦（趋势）")

            HStack {
                Text("第\(r.changed.number)卦 · \(r.changed.name)（\(r.changed.alias)）")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(Theme.ink)
                Spacer()
                Text(String(r.changed.unicodeSymbol))
                    .font(.system(size: 22))
                    .foregroundColor(Theme.brand)
            }
            .padding(.top, 6)

            labeled("象义", XiangYi.of(r.changed.number), bodyColor: Theme.inkMuted)
                .padding(.top, 8)
            labeled("卦辞", r.changed.judgment, bodyColor: Theme.ink)
                .padding(.top, 8)
            labeled("白话", GuaciBaihua.of(r.changed.number), bodyColor: Theme.inkMuted)
                .padding(.top, 6)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
    }

    // MARK: - 所问

    private var questionCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionTitle("所问")
            Text(flow.question)
                .font(.system(size: 15))
                .foregroundColor(Theme.ink)
                .lineSpacing(3)
                .padding(.top, 6)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
    }

    // MARK: - 通用解读（无提问）

    private func genericCard(_ r: DivinationResult) -> some View {
        let g = Reading.genericReading(r)
        return VStack(alignment: .leading, spacing: 0) {
            sectionTitle("通用解读", color: Theme.cinnabarDark)
            labeled("事业", g.career, bodyColor: Theme.ink).padding(.top, 10)
            labeled("感情", g.love, bodyColor: Theme.ink).padding(.top, 10)
            labeled("健康", g.health, bodyColor: Theme.ink).padding(.top, 10)
            labeled("抉择", g.decision, bodyColor: Theme.ink).padding(.top, 10)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle(fill: Theme.readCard, border: Theme.cinnabar)
    }

    // MARK: - AI 解卦（有提问）

    private var aiCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionTitle(flow.mode.isEmpty ? "解卦" : flow.mode)
            if !flow.error.isEmpty {
                Text(flow.error)
                    .font(.system(size: 15))
                    .foregroundColor(Theme.ink)
                    .lineSpacing(4)
                    .padding(.top, 8)
            } else {
                Text(flow.reply)
                    .font(.system(size: 15))
                    .foregroundColor(Theme.ink)
                    .lineSpacing(4)
                    .padding(.top, 8)
            }
            if !flow.hint.isEmpty {
                Text(flow.hint)
                    .font(.system(size: 14))
                    .foregroundColor(Theme.inkMuted)
                    .lineSpacing(4)
                    .padding(.top, 6)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardStyle()
    }

    // MARK: - 复用

    private func sectionTitle(_ text: String, color: Color = Theme.cinnabar) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .bold))
            .foregroundColor(color)
    }

    private func labeled(_ label: String, _ body: String, bodyColor: Color) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text(label)
                .font(.system(size: 14))
                .foregroundColor(Theme.brandDark)
                .frame(width: 34, alignment: .leading)
            Text(body)
                .font(.system(size: 14))
                .foregroundColor(bodyColor)
                .lineSpacing(4)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}