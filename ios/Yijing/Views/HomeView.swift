import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var flow: CastFlow
    @StateObject private var location = LocationService()

    @State private var question = ""
    @State private var showHistory = false
    @State private var showSettings = false
    @State private var showCondition = false
    @State private var now = Date()
    @FocusState private var questionFocused: Bool

    private let timer = Timer.publish(every: 30, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            topBar
                .padding(.horizontal, 24)
                .padding(.top, 8)

            VStack(alignment: .leading, spacing: 0) {
                Text(solarText)
                    .font(.system(size: 13))
                    .foregroundColor(Theme.inkMuted)
                    .lineSpacing(3)
                    .padding(.top, 8)

                questionEditor
                    .padding(.top, 24)

                Divider()
                    .background(Theme.divider)

                Spacer(minLength: 0)

                sealButton

                Text("轻点：当前时空　｜　长按：设定条件")
                    .font(.system(size: 13))
                    .foregroundColor(Theme.inkMuted)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 16)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .background(Theme.paper.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            location.request()
            now = Date()
        }
        .onReceive(timer) { _ in now = Date() }
        .sheet(isPresented: $showHistory) { HistoryView() }
        .sheet(isPresented: $showSettings) { SettingsView() }
        .sheet(isPresented: $showCondition) {
            ConditionSheetView(
                onTime: { date in
                    showCondition = false
                    flow.start(timeResult(date), question: question)
                },
                onNumber: { n1, n2, n3 in
                    showCondition = false
                    flow.start(Divination.fromNumbers(n1, n2, n3), question: question)
                },
                onDirection: { dir in
                    showCondition = false
                    flow.start(Divination.fromDirection(dir, trueSolarShichen()), question: question)
                }
            )
        }
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("收起") { questionFocused = false }
            }
        }
    }

    // MARK: - 顶栏

    private var topBar: some View {
        HStack {
            Text("周易小卦")
                .font(.system(size: 24, weight: .bold))
                .foregroundColor(Theme.ink)
            Spacer()
            // 「我的」= 历史记录入口；长按隐藏入口打开设置（对外看不出设置入口）
            Text("我的")
                .font(.system(size: 15))
                .foregroundColor(Theme.brand)
                .padding(8)
                .contentShape(Rectangle())
                .onTapGesture { showHistory = true }
                .onLongPressGesture(minimumDuration: 1.2) { showSettings = true }
        }
    }

    // MARK: - 问题输入（放大）

    private var questionEditor: some View {
        ZStack(alignment: .topLeading) {
            if question.isEmpty {
                Text("默问即可，可不写…")
                    .font(.system(size: 16))
                    .foregroundColor(Theme.inkMuted)
                    .padding(.top, 10)
                    .padding(.leading, 6)
            }
            TextEditor(text: $question)
                .font(.system(size: 16))
                .foregroundColor(Theme.ink)
                .scrollContentBackground(.hidden)
                .background(Color.clear)
                .focused($questionFocused)
                .frame(height: 160)
        }
    }

    // MARK: - 主印章按钮（整体下移）

    @State private var pressed = false

    private var sealButton: some View {
        SealView()
            .frame(width: 150, height: 150)
            .scaleEffect(pressed ? 0.96 : 1)
            .opacity(pressed ? 0.9 : 1)
            .animation(.easeOut(duration: 0.12), value: pressed)
            .frame(maxWidth: .infinity)
            .padding(.top, 56)
            .onTapGesture { castNow() }
            .onLongPressGesture(minimumDuration: 0.5, pressing: { p in pressed = p }) {
                showCondition = true
            }
    }

    // MARK: - 起卦

    private func castNow() {
        flow.start(timeResult(Date()), question: question)
    }

    private func timeResult(_ date: Date) -> DivinationResult {
        let c = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
        let y = c.year ?? 1, m = c.month ?? 1, d = c.day ?? 1, h = c.hour ?? 0, min = c.minute ?? 0
        if let ln = LunarCalendar.fromGregorian(year: y, month: m, day: d, hour: h, minute: min) {
            return Divination.fromLunarNumbers(yearZhi: ln.yearZhi, month: ln.month, day: ln.day, hourZhi: ln.hourZhi)
        }
        let doy = Calendar.current.ordinality(of: .day, in: .year, for: date) ?? 0
        let minute = Double(h * 60 + min)
        let ts = SolarTime.trueSolarMinutes(stdClockMinutes: minute, longitudeEast: location.longitudeEast, dayOfYear: doy)
        let sc = SolarTime.shichen(trueSolarMinutes: ts)
        return Divination.fromDateTime(y, m, d, sc + 1)
    }

    private func trueSolarShichen() -> Int {
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let minute = Double((c.hour ?? 0) * 60 + (c.minute ?? 0))
        let doy = Calendar.current.ordinality(of: .day, in: .year, for: Date()) ?? 0
        let ts = SolarTime.trueSolarMinutes(stdClockMinutes: minute, longitudeEast: location.longitudeEast, dayOfYear: doy)
        return SolarTime.shichen(trueSolarMinutes: ts)
    }

    // MARK: - 真太阳时

    private var solarText: String {
        let c = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: now)
        let minute = Double((c.hour ?? 0) * 60 + (c.minute ?? 0))
        let doy = Calendar.current.ordinality(of: .day, in: .year, for: now) ?? 0
        let ts = SolarTime.trueSolarMinutes(stdClockMinutes: minute, longitudeEast: location.longitudeEast, dayOfYear: doy)
        let norm = ((ts.truncatingRemainder(dividingBy: 1440)) + 1440).truncatingRemainder(dividingBy: 1440)
        let hh = Int(norm) / 60
        let mm = Int(norm) % 60
        let sc = SolarTime.shichen(trueSolarMinutes: ts)
        let lon = String(format: "%.1f", location.longitudeEast)
        var text = String(format: "真太阳时 %02d:%02d · %@时 · 东经%@°", hh, mm, SolarTime.shichenNames[sc], lon)
        if let ln = LunarCalendar.fromGregorian(year: c.year ?? 1, month: c.month ?? 1, day: c.day ?? 1, hour: c.hour ?? 0, minute: c.minute ?? 0) {
            text += "\n四柱：\(ln.bazi)"
        }
        return text
    }
}