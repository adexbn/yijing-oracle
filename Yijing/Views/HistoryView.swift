import SwiftUI

struct HistoryView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var records = HistoryStore.list()
    @State private var showClearConfirm = false

    var body: some View {
        VStack(spacing: 0) {
            header

            if records.isEmpty {
                Spacer()
                Text("暂无记录，先起一卦吧。")
                    .font(.system(size: 14))
                    .foregroundColor(Theme.inkMuted)
                Spacer()
            } else {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(records) { rec in
                            row(rec)
                            Divider().background(Theme.divider)
                        }
                    }
                    .padding(.horizontal, 20)
                }
            }
        }
        .background(Theme.paper.ignoresSafeArea())
        .alert("确认清空全部记录？", isPresented: $showClearConfirm) {
            Button("清空", role: .destructive) {
                HistoryStore.clear()
                records = []
            }
            Button("取消", role: .cancel) {}
        }
    }

    private var header: some View {
        HStack {
            Button { dismiss() } label: {
                Text("‹ 返回").font(.system(size: 15)).foregroundColor(Theme.brand).padding(8)
            }
            .buttonStyle(.plain)
            Spacer()
            Text("我的")
                .font(.system(size: 20, weight: .bold))
                .foregroundColor(Theme.ink)
            Spacer()
            Button {
                showClearConfirm = !records.isEmpty
            } label: {
                Text("清空").font(.system(size: 15)).foregroundColor(Theme.brand).padding(8)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 4)
    }

    private func row(_ rec: HistoryRecord) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(rec.method) · \(format(rec.time))")
                .font(.system(size: 12))
                .foregroundColor(Theme.inkMuted)
            if !rec.question.isEmpty {
                Text("问：\(rec.question)")
                    .font(.system(size: 15))
                    .foregroundColor(Theme.ink)
            }
            Text(summary(rec))
                .font(.system(size: 15))
                .foregroundColor(Theme.ink)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 20)
    }

    private func summary(_ rec: HistoryRecord) -> String {
        var s = rec.original
        if rec.movingLine > 0 { s += " · 动爻\(rec.movingLine)" }
        s += "  →  \(rec.changed)"
        return s
    }

    private func format(_ time: TimeInterval) -> String {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd HH:mm"
        return df.string(from: Date(timeIntervalSince1970: time))
    }
}