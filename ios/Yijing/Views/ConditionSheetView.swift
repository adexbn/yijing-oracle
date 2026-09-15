import SwiftUI

/// 起卦条件面板：指定时间 / 指定数字 / 指定方位，通过 NavigationLink 进入各自输入页。
struct ConditionSheetView: View {
    let onTime: (Date) -> Void
    let onNumber: (Int, Int, Int) -> Void
    let onDirection: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 0) {
                Text("设定条件")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(Theme.ink)
                    .padding(.horizontal, 20)
                    .padding(.top, 20)
                Text("轻点主章直接以当前时空起卦，或自选条件")
                    .font(.system(size: 12))
                    .foregroundColor(Theme.inkMuted)
                    .padding(.horizontal, 20)
                    .padding(.top, 2)

                VStack(spacing: 10) {
                    NavigationLink {
                        TimePickView(onConfirm: onTime)
                    } label: {
                        row("指定时间")
                    }
                    NavigationLink {
                        NumberInputView(onConfirm: onNumber)
                    } label: {
                        row("指定数字")
                    }
                    NavigationLink {
                        DirectionInputView(onConfirm: onDirection)
                    } label: {
                        row("指定方位")
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)

                Spacer()
            }
            .background(Theme.paper.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("取消") { dismiss() }
                        .foregroundColor(Theme.inkMuted)
                }
            }
        }
    }

    private func row(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 15))
            .foregroundColor(Theme.ink)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .cardStyle()
    }
}

// MARK: - 指定时间

private struct TimePickView: View {
    let onConfirm: (Date) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var date = Date()

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("指定时间")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(Theme.ink)
                .padding(.horizontal, 20)
                .padding(.top, 20)
            Text("以某件事发生的具体公历日期起卦，常用以占问过往之事或某个特定的日子。")
                .font(.system(size: 12))
                .foregroundColor(Theme.inkMuted)
                .lineSpacing(2)
                .padding(.horizontal, 20)
                .padding(.top, 6)

            DatePicker("", selection: $date, displayedComponents: .date)
                .datePickerStyle(.graphical)
                .labelsHidden()
                .padding(.horizontal, 12)
                .padding(.top, 8)

            Spacer()

            HStack {
                Spacer()
                Button("取消") { dismiss() }
                    .foregroundColor(Theme.inkMuted)
                    .padding(10)
                Button("起卦") {
                    onConfirm(date)
                }
                .foregroundColor(Theme.cinnabar)
                .fontWeight(.bold)
                .padding(10)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
        }
        .background(Theme.paper.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
    }
}

// MARK: - 指定数字

private struct NumberInputView: View {
    let onConfirm: (Int, Int, Int) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var n1 = ""
    @State private var n2 = ""
    @State private var n3 = ""
    @State private var showError = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("报数起卦")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(Theme.ink)
                .padding(.horizontal, 20)
                .padding(.top, 20)
            Text("心中默想所问之事，随口报出三个数字，依次对应上卦、下卦与动爻。")
                .font(.system(size: 12))
                .foregroundColor(Theme.inkMuted)
                .lineSpacing(2)
                .padding(.horizontal, 20)
                .padding(.top, 6)

            HStack(spacing: 10) {
                field($n1, "第一数", "上卦")
                field($n2, "第二数", "下卦")
                field($n3, "第三数", "动爻")
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)

            Spacer()

            HStack {
                Spacer()
                Button("取消") { dismiss() }
                    .foregroundColor(Theme.inkMuted)
                    .padding(10)
                Button("起卦") {
                    if let a = Int(n1.trimmingCharacters(in: .whitespaces)), a > 0,
                       let b = Int(n2.trimmingCharacters(in: .whitespaces)), b > 0,
                       let c = Int(n3.trimmingCharacters(in: .whitespaces)), c > 0 {
                        onConfirm(a, b, c)
                    } else {
                        showError = true
                    }
                }
                .foregroundColor(Theme.cinnabar)
                .fontWeight(.bold)
                .padding(10)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
        }
        .background(Theme.paper.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
        .alert("请填满三个大于 0 的数字", isPresented: $showError) {
            Button("好", role: .cancel) {}
        }
    }

    private func field(_ text: Binding<String>, _ hint: String, _ label: String) -> some View {
        VStack(spacing: 4) {
            TextField(hint, text: text)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.center)
                .font(.system(size: 16))
                .foregroundColor(Theme.ink)
                .padding(14)
                .cardStyle()
            Text(label)
                .font(.system(size: 11))
                .foregroundColor(Theme.inkMuted)
        }
    }
}

// MARK: - 指定方位

private struct DirectionInputView: View {
    let onConfirm: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    private let grid = [
        "西北", "北", "东北",
        "西", nil, "东",
        "西南", "南", "东南"
    ]

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("指定方位")
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(Theme.ink)
                .padding(.horizontal, 20)
                .padding(.top, 20)
            Text("以当前所向或心中默念的方位起卦，方位定上卦、时辰定下卦。")
                .font(.system(size: 12))
                .foregroundColor(Theme.inkMuted)
                .lineSpacing(2)
                .padding(.horizontal, 20)
                .padding(.top, 6)

            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(0..<9, id: \.self) { i in
                    if let dir = grid[i] {
                        let tg = Trigram.fromDirection(dir)
                        Button {
                            onConfirm(dir)
                        } label: {
                            VStack(spacing: 2) {
                                Text(dir)
                                    .font(.system(size: 13))
                                    .foregroundColor(Theme.ink)
                                Text("\(tg.symbol)\(tg.label)·\(tg.nature)")
                                    .font(.system(size: 11))
                                    .foregroundColor(Theme.inkMuted)
                            }
                            .frame(maxWidth: .infinity)
                            .frame(height: 64)
                            .cardStyle()
                        }
                        .buttonStyle(.plain)
                    } else {
                        Text("·")
                            .font(.system(size: 30))
                            .foregroundColor(Theme.inkMuted)
                            .frame(maxWidth: .infinity)
                            .frame(height: 64)
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)

            Spacer()
        }
        .background(Theme.paper.ignoresSafeArea())
        .navigationBarBackButtonHidden(true)
    }
}