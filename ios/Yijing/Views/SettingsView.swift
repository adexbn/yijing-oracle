import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SettingsView: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    @State private var provider: String
    @State private var baseUrl: String
    @State private var apiKey: String
    @State private var model: String
    @State private var cloudEnabled: Bool

    @State private var downloading = false
    @State private var progress = 0
    @State private var downloaded = ModelManager.isDownloaded()
    @State private var showImporter = false
    @State private var showLog = false
    @State private var logText = ""

    init() {
        let c = AppSettings.shared.config
        _provider = State(initialValue: c.provider)
        _baseUrl = State(initialValue: c.baseUrl)
        _apiKey = State(initialValue: c.apiKey)
        _model = State(initialValue: c.model)
        _cloudEnabled = State(initialValue: c.cloudEnabled)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header

                // 解读方式
                section("解读方式")
                Toggle(isOn: $cloudEnabled) {
                    Text("联网解读（关闭时用本地小模型）")
                        .font(.system(size: 15))
                        .foregroundColor(Theme.ink)
                }
                .tint(Theme.brand)
                .cardStyle()
                .padding(.horizontal, 24)

                // 服务商
                section("服务商")
                VStack(spacing: 0) {
                    Picker("服务商", selection: providerBinding) {
                        ForEach(AppSettings.providers, id: \.name) { p in
                            Text(p.name).tag(p.name)
                        }
                    }
                    .pickerStyle(.menu)
                    .font(.system(size: 15))
                }
                .cardStyle()
                .padding(.horizontal, 24)

                // 接口配置
                section("接口配置")
                VStack(spacing: 12) {
                    inputRow("Base URL", $baseUrl)
                    inputRow("API Key", $apiKey)
                    inputRow("模型", $model)
                }
                .padding(16)
                .cardStyle()
                .padding(.horizontal, 24)

                Button(action: save) {
                    Text("保存")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Theme.brand))
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 24)

                // 本地模型
                section("本地小模型（离线解读，不联网）")
                VStack(alignment: .leading, spacing: 12) {
                    Text(modelStatusText)
                        .font(.system(size: 14))
                        .foregroundColor(Theme.ink)
                    if downloading {
                        ProgressView(value: Double(progress), total: 100)
                    }
                    HStack(spacing: 12) {
                        Button(action: toggleDownload) {
                            Text(buttonTitle)
                                .font(.system(size: 15))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                .background(RoundedRectangle(cornerRadius: 10).fill(downloaded ? Theme.cinnabar : Theme.brand))
                        }
                        .buttonStyle(.plain)
                        .disabled(downloading)

                        Button {
                            showImporter = true
                        } label: {
                            Text("本地导入")
                                .font(.system(size: 15))
                                .foregroundColor(Theme.brand)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                .background(RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.brand, lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                        .disabled(downloading)
                    }

                    linkRow("官方下载", ModelManager.modelURL)
                    linkRow("镜像下载", ModelManager.modelURLMirror)
                }
                .padding(16)
                .cardStyle()
                .padding(.horizontal, 24)

                // 诊断
                section("诊断")
                Button {
                    logText = YjLog.readLog()
                    showLog = true
                } label: {
                    Text("查看崩溃日志")
                        .font(.system(size: 15))
                        .foregroundColor(Theme.ink)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(RoundedRectangle(cornerRadius: 10).strokeBorder(Theme.inkMuted.opacity(0.4), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 24)

                .padding(.bottom, 24)
            }
        }
        .background(Theme.paper.ignoresSafeArea())
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.data]) { result in
            handleImport(result)
        }
        .sheet(isPresented: $showLog) {
            logSheet
        }
    }

    private var providerBinding: Binding<String> {
        Binding(
            get: { provider },
            set: { new in
                provider = new
                let (u, m) = AppSettings.defaults(for: new)
                if !u.isEmpty { baseUrl = u }
                if !m.isEmpty { model = m }
            }
        )
    }

    private var header: some View {
        HStack {
            Button { dismiss() } label: {
                Text("‹ 返回").font(.system(size: 15)).foregroundColor(Theme.brand).padding(8)
            }
            .buttonStyle(.plain)
            Spacer()
            Text("设置")
                .font(.system(size: 20, weight: .bold))
                .foregroundColor(Theme.ink)
            Spacer()
            Color.clear.frame(width: 60, height: 1)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    private func section(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 13))
            .foregroundColor(Theme.inkMuted)
            .padding(.horizontal, 24)
    }

    private var logSheet: some View {
        VStack(spacing: 0) {
            HStack {
                Button("关闭") { showLog = false }
                Spacer()
                Text("崩溃日志")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(Theme.ink)
                Spacer()
                Button("复制") { UIPasteboard.general.string = logText }
            }
            .font(.system(size: 15))
            .foregroundColor(Theme.brand)
            .padding(12)
            Divider()
            ScrollView {
                Text(logText)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(Theme.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
            }
        }
        .background(Theme.paper.ignoresSafeArea())
    }

    private func inputRow(_ label: String, _ text: Binding<String>) -> some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.system(size: 13))
                .foregroundColor(Theme.inkMuted)
                .frame(width: 76, alignment: .leading)
            TextField(label, text: text)
                .font(.system(size: 15))
                .foregroundColor(Theme.ink)
                .autocapitalization(.none)
                .disableAutocorrection(true)
        }
    }

    private func save() {
        let (defUrl, defModel) = AppSettings.defaults(for: provider)
        let b = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let m = model.trimmingCharacters(in: .whitespacesAndNewlines)
        settings.config = AiConfig(
            provider: provider,
            baseUrl: b.isEmpty ? defUrl : b,
            apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines),
            model: m.isEmpty ? defModel : m,
            cloudEnabled: cloudEnabled
        )
        dismiss()
    }

    // MARK: - 模型

    private var modelStatusText: String {
        if downloading { return "下载中 \(progress)%" }
        if let size = ModelManager.modelFileSize() {
            if size > ModelManager.minModelBytes {
                return "已就绪 · \(ModelManager.formatSize(size))"
            }
            return "文件不完整（当前 \(ModelManager.formatSize(size))，应约 1.2GB），请重新下载或导入"
        }
        return "未下载（约 1.2GB，建议在 Wi-Fi 下进行）"
    }

    private var buttonTitle: String {
        if downloading { return "下载中" }
        return downloaded ? "删除模型" : "下载模型"
    }

    private func toggleDownload() {
        if downloaded {
            ModelManager.delete()
            downloaded = false
        } else {
            startDownload()
        }
    }

    private func startDownload() {
        downloading = true
        progress = 0
        Task {
            do {
                _ = try await ModelManager.download { pct in
                    DispatchQueue.main.async {
                        progress = pct
                        downloaded = ModelManager.isDownloaded()
                    }
                }
                downloaded = ModelManager.isDownloaded()
            } catch {
                downloaded = ModelManager.isDownloaded()
            }
            downloading = false
        }
    }

    private func handleImport(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            do {
                // 流式复制，避免 Data(contentsOf:) 把 1.2GB 整个读进内存导致 OOM
                _ = try ModelManager.importFrom(fileURL: url)
                downloaded = ModelManager.isDownloaded()
            } catch {
                downloaded = ModelManager.isDownloaded()
            }
        case .failure:
            break
        }
    }

    private func linkRow(_ label: String, _ url: String) -> some View {
        HStack(spacing: 8) {
            Text(label)
                .font(.system(size: 13))
                .foregroundColor(Theme.inkMuted)
                .frame(width: 64, alignment: .leading)
            Text(url)
                .font(.system(size: 12))
                .foregroundColor(Theme.inkMuted)
                .lineLimit(1)
                .truncationMode(.middle)
            Spacer()
            Button {
                UIPasteboard.general.string = url
            } label: {
                Text("复制")
                    .font(.system(size: 12))
                    .foregroundColor(Theme.brand)
            }
            .buttonStyle(.plain)
        }
    }
}