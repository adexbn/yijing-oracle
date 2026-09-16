import SwiftUI

/// 首次启动初始化页。
///
/// 为什么要有这一页：解卦用的本地小模型（约 1.2GB）不打进安装包，否则 App 体积无法接受。
/// 所以第一次使用必须先把模型拿到本地。这里做两件事：
/// 1. 从国内镜像（hf-mirror.com）自动下载，速度远快于原站；
/// 2. 用太极 + 环形进度把等待过程做得好看一些，并明确告诉用户「只需一次」。
struct OnboardingView: View {
    /// 初始化完成回调（下载成功并预热后调用）。
    var onFinished: () -> Void

    private enum Phase: Equatable {
        case preparing      // 检查本地是否已有模型
        case downloading    // 正在下载
        case warming        // 下载完成，正在预热（避免第一次解卦还要等）
        case failed(String) // 失败，可重试
    }

    @State private var phase: Phase = .preparing
    @State private var progress: Double = 0
    @State private var shown = false

    var body: some View {
        ZStack {
            Theme.paper.ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer(minLength: 0)

                ringView
                    .opacity(shown ? 1 : 0)
                    .scaleEffect(shown ? 1 : 0.9)
                    .animation(.easeOut(duration: 0.7), value: shown)

                Text("周易小卦")
                    .font(.system(size: 26, weight: .bold, design: .serif))
                    .foregroundColor(Theme.ink)
                    .padding(.top, 36)

                Text(headline)
                    .font(.system(size: 15))
                    .foregroundColor(Theme.cinnabar)
                    .padding(.top, 12)

                Text(detail)
                    .font(.system(size: 13))
                    .foregroundColor(Theme.inkMuted)
                    .multilineTextAlignment(.center)
                    .lineSpacing(5)
                    .padding(.top, 10)
                    .padding(.horizontal, 40)
                    .fixedSize(horizontal: false, vertical: true)

                if case .failed = phase {
                    VStack(spacing: 16) {
                        Button { start() } label: {
                            Text("重新下载")
                                .font(.system(size: 15))
                                .foregroundColor(.white)
                                .padding(.horizontal, 30)
                                .padding(.vertical, 11)
                                .background(Capsule().fill(Theme.cinnabar))
                        }
                        .buttonStyle(.plain)

                        // 网络实在不通时留个出口，不要把人困在引导页。
                        Button { onFinished() } label: {
                            Text("暂时跳过，稍后再下载")
                                .font(.system(size: 13))
                                .foregroundColor(Theme.inkMuted)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.top, 26)
                } else {
                    progressBlock
                        .padding(.top, 30)
                        .padding(.horizontal, 56)
                }

                Spacer(minLength: 0)

                Text("仅需一次　·　完成后可完全离线解卦\n模型存放于 App 数据目录，重装或升级不会重复下载")
                    .font(.system(size: 11))
                    .foregroundColor(Theme.inkMuted.opacity(0.85))
                    .multilineTextAlignment(.center)
                    .lineSpacing(4)
                    .padding(.horizontal, 36)
                    .padding(.bottom, 34)
            }
        }
        .onAppear {
            withAnimation { shown = true }
            start()
        }
    }

    // MARK: - 视觉

    /// 太极 + 环形进度：外圈随下载进度生长。
    private var ringView: some View {
        ZStack {
            Circle()
                .fill(Color.white.opacity(0.75))
                .shadow(color: Theme.brand.opacity(0.12), radius: 18, x: 0, y: 8)
            Circle()
                .stroke(Theme.divider, lineWidth: 2)
            Circle()
                .trim(from: 0, to: CGFloat(max(0.004, progress)))
                .stroke(
                    Theme.cinnabar,
                    style: StrokeStyle(lineWidth: 3, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
                .animation(.easeOut(duration: 0.28), value: progress)
            TaijiMark()
                .frame(width: 108, height: 108)
                .scaleEffect(shown ? 1 : 0.94)
                .animation(
                    .easeInOut(duration: 3.2).repeatForever(autoreverses: true),
                    value: shown
                )
        }
        .frame(width: 172, height: 172)
    }

    private var progressBlock: some View {
        VStack(spacing: 12) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.divider)
                    Capsule()
                        .fill(Theme.cinnabar)
                        .frame(width: max(8, geo.size.width * CGFloat(progress)))
                }
            }
            .frame(height: 6)
            .animation(.easeOut(duration: 0.28), value: progress)

            HStack {
                Text(statusText)
                    .font(.system(size: 12))
                    .foregroundColor(Theme.inkMuted)
                Spacer()
                Text("\(Int((progress * 100).rounded()))%")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(Theme.cinnabar)
                    .monospacedDigit()
            }
        }
    }

    // MARK: - 文案

    private var headline: String {
        switch phase {
        case .preparing: return "首次使用需要初始化"
        case .downloading: return "首次使用需要初始化"
        case .warming: return "初始化即将完成"
        case .failed: return "初始化未完成"
        }
    }

    private var detail: String {
        switch phase {
        case .preparing:
            return "正在检查本地解卦模型…"
        case .downloading:
            return "正在下载解卦模型（约 1.2GB，国内镜像加速）\n请保持网络连接，稍候片刻"
        case .warming:
            return "正在载入模型，之后每次解卦都能秒出结果"
        case .failed(let msg):
            return "\(msg)\n请检查网络后点击下方重试，建议在 Wi-Fi 环境下进行。"
        }
    }

    private var statusText: String {
        switch phase {
        case .preparing: return "准备中"
        case .downloading: return "下载中"
        case .warming: return "载入模型"
        case .failed: return "未完成"
        }
    }

    // MARK: - 流程

    @MainActor
    private func start() {
        phase = .preparing
        progress = 0

        Task {
            if ModelManager.isDownloaded() {
                await finalize()
                return
            }
            phase = .downloading
            do {
                _ = try await ModelManager.download { pct in
                    // 留最后 1% 给「载入模型」阶段，避免进度条 100% 卡住不动的观感
                    Task { @MainActor in
                        progress = min(0.99, Double(pct) / 100.0)
                    }
                }
                await finalize()
            } catch {
                if Task.isCancelled { return }
                phase = .failed(error.localizedDescription)
            }
        }
    }

    /// 下载完成（或本地已有）后预热模型，再回到主界面。
    @MainActor
    private func finalize() async {
        progress = 1
        phase = .warming
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            DispatchQueue.global(qos: .userInitiated).async {
                // 先把后端（Metal 着色器库）和模型都读好，首次解卦就不必再等。
                LlamaCPP.warmUp()
                LlamaCPP.preloadModel()
                cont.resume()
            }
        }
        // 让视觉状态停留一下，避免进度条一闪而过
        try? await Task.sleep(nanoseconds: 400_000_000)
        onFinished()
    }
}

/// 太极标记：纸色为阳、墨色为阴，含双鱼眼。与 App 图标同一套构图。
struct TaijiMark: View {
    var light: Color = Theme.paper
    var dark: Color = Theme.ink

    var body: some View {
        Canvas { ctx, size in
            let r = min(size.width, size.height) / 2
            let c = CGPoint(x: size.width / 2, y: size.height / 2)

            func disc(_ cx: CGFloat, _ cy: CGFloat, _ rr: CGFloat) -> Path {
                Path(ellipseIn: CGRect(x: cx - rr, y: cy - rr, width: rr * 2, height: rr * 2))
            }

            ctx.fill(disc(c.x, c.y, r), with: .color(light))

            var half = Path()
            half.addArc(
                center: c,
                radius: r,
                startAngle: .degrees(-90),
                endAngle: .degrees(90),
                clockwise: false
            )
            half.closeSubpath()
            ctx.fill(half, with: .color(dark))

            ctx.fill(disc(c.x, c.y - r / 2, r / 2), with: .color(dark))
            ctx.fill(disc(c.x, c.y + r / 2, r / 2), with: .color(light))
            ctx.fill(disc(c.x, c.y - r / 2, r / 8), with: .color(light))
            ctx.fill(disc(c.x, c.y + r / 2, r / 8), with: .color(dark))

            ctx.stroke(disc(c.x, c.y, r - 0.75), with: .color(dark.opacity(0.75)), lineWidth: 1.5)
        }
    }
}
