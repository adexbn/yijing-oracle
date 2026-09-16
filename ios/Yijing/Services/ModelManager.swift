import Foundation

/// 本地小模型（GGUF）的下载与文件管理。模型不随 App 打包，安装后按需下载或本地导入。
enum ModelManager {

    static let modelFile = "qwen3-1.7b-q4_k_m.gguf"
    static let modelURL = "https://huggingface.co/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
    static let modelURLMirror = "https://hf-mirror.com/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"

    /// 模型文件完整的最小字节数。Qwen3-1.7B Q4_K_M 约 1223MB。
    /// 下载/导入中断会留下不完整文件，若不校验大小，llama.cpp 用 mmap 加载时
    /// 访问越界会触发 SIGBUS 直接崩溃（而非可捕获的加载失败）。
    static let minModelBytes: Int64 = 1100 * 1024 * 1024

    /// 下载源顺序：备用源优先，原站兜底。
    static let sources = [modelURLMirror, modelURL]

    /// 单次「等不到数据」的上限（秒）。
    ///
    /// 注意这不是整个下载的总时长上限：`timeoutIntervalForRequest` 的语义是
    /// 「等待更多数据的超时」，每收到一批新数据计时器就会重置，
    /// 所以网速慢不会误杀大文件下载；只有真正断流（60 秒一个字节都不来）才失败，
    /// 这正是我们要的——网络不稳时快速暴露错误，而不是无限期挂着。
    private static let stallTimeout: TimeInterval = 60

    private static var modelsDir: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("models", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func modelFileURL() -> URL {
        modelsDir.appendingPathComponent(modelFile)
    }

    /// 返回模型文件的字节数，文件不存在返回 nil。
    static func modelFileSize() -> Int64? {
        let f = modelFileURL()
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: f.path),
              let size = attrs[.size] as? Int64 else { return nil }
        return size
    }

    static func isDownloaded() -> Bool {
        (modelFileSize() ?? 0) > minModelBytes
    }

    static func delete() {
        let f = modelFileURL()
        try? FileManager.default.removeItem(at: f)
        try? FileManager.default.removeItem(at: f.appendingPathExtension("part"))
    }

    /// 从本地文件流式导入模型（避免一次性把 1.2GB 读进内存导致 OOM）。
    /// 分块读取源文件并写入沙盒，内存占用控制在约 1MB。
    static func importFrom(fileURL src: URL) throws -> URL {
        let dest = modelFileURL()
        try? FileManager.default.removeItem(at: dest)
        let input = try FileHandle(forReadingFrom: src)
        defer { try? input.close() }
        FileManager.default.createFile(atPath: dest.path, contents: nil)
        let output = try FileHandle(forWritingTo: dest)
        defer { try? output.close() }
        while true {
            let chunk = input.readData(ofLength: 1024 * 1024)
            if chunk.isEmpty { break }
            try output.write(contentsOf: chunk)
        }
        return dest
    }

    /// 依次尝试所有源下载，通过 [onProgress] 回调 0..100。先写 .part，成功后重命名。
    ///
    /// 支持取消：外层 `Task` 被 cancel 时，底层的下载任务会被立刻掐断，
    /// 半成品文件会被删掉，并抛出 `CancellationError`（调用方据此区分「取消」和「失败」）。
    static func download(onProgress: @escaping (Int) -> Void) async throws -> URL {
        var lastError: Error?
        for url in sources {
            if Task.isCancelled { throw CancellationError() }
            do {
                return try await downloadFrom(url, onProgress: onProgress)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                lastError = error
            }
        }
        throw lastError ?? ApiError("下载失败")
    }

    private static func downloadFrom(_ urlString: String, onProgress: @escaping (Int) -> Void) async throws -> URL {
        guard let url = URL(string: urlString) else { throw ApiError("下载地址无效") }
        let dest = modelFileURL()
        let tmp = dest.appendingPathExtension("part")
        try? FileManager.default.removeItem(at: tmp)
        try FileManager.default.createDirectory(
            at: tmp.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        var req = URLRequest(url: url)
        req.setValue("Mozilla/5.0 (iPhone) yijing-app", forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = stallTimeout

        let delegate = DownloadDelegate(stagingURL: tmp, onProgress: onProgress)
        defer { delegate.invalidateAndCancel() }

        let result: (status: Int, bytes: Int64)
        do {
            result = try await delegate.start(request: req)
        } catch {
            try? FileManager.default.removeItem(at: tmp)
            if Task.isCancelled { throw CancellationError() }
            throw error
        }

        guard (200...299).contains(result.status) else {
            try? FileManager.default.removeItem(at: tmp)
            throw ApiError("下载失败 HTTP \(result.status)")
        }
        // 服务端给了长度就必须收满，否则算断流（半截文件会让 mmap 加载直接崩）
        if result.bytes <= minModelBytes {
            try? FileManager.default.removeItem(at: tmp)
            throw ApiError("下载的文件不完整，请重试")
        }
        onProgress(100)

        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: tmp, to: dest)
        } catch {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: tmp, to: dest)
            try? FileManager.default.removeItem(at: tmp)
        }
        return dest
    }

    static func formatSize(_ bytes: Int64) -> String {
        String(format: "%.0f MB", Double(bytes) / (1024 * 1024))
    }

    /// 通用错误包装。
    struct ApiError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
        init(_ message: String) { self.message = message }
    }
}

/// 用 `URLSessionDownloadTask` 接收数据：系统直接把字节写进文件，
/// 我们只在进度和结束时被回调。
///
/// 之前的实现是 `for try await byte in URLSession.shared.bytes(for:)`，
/// 1.2GB 意味着循环 12 亿次、每次都要过一次 async 挂起点，手机上慢得离谱
/// （实测 iPhone 上要么卡很久要么干脆失败），这是「手机上很慢」的主因。
/// 换成系统下载任务后，数据路径回到原生网络栈，速度与「用浏览器下载」同量级。
private final class DownloadDelegate: NSObject, URLSessionDownloadDelegate {

    private let stagingURL: URL
    private let onProgress: (Int) -> Void

    private var continuation: CheckedContinuation<(status: Int, bytes: Int64), Error>?
    private var finished = false
    private var session: URLSession?
    private var task: URLSessionDownloadTask?

    init(stagingURL: URL, onProgress: @escaping (Int) -> Void) {
        self.stagingURL = stagingURL
        self.onProgress = onProgress
        super.init()
    }

    /// 启动下载并等待结果。返回 HTTP 状态码和实际收到的字节数。
    func start(request: URLRequest) async throws -> (status: Int, bytes: Int64) {
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1 // 回调串行，省掉内部加锁
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: queue)
        self.session = session
        let task = session.downloadTask(with: request)
        self.task = task

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<(status: Int, bytes: Int64), Error>) in
                self.continuation = cont
                task.resume()
            }
        } onCancel: {
            // 用户点了「取消下载」：立刻掐断，不再白等
            task.cancel()
        }
    }

    func invalidateAndCancel() {
        // 已经成功结束时用 finishTasksAndInvalidate 会让 session 多活一会儿，
        // 这里统一走 cancel 版本：任务已结束，不会有副作用。
        session?.invalidateAndCancel()
        session = nil
        task = nil
    }

    // MARK: - URLSessionDownloadDelegate

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard totalBytesExpectedToWrite > 0 else { return }
        let pct = Int(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite) * 100)
        onProgress(min(99, pct)) // 留 1% 给「载入模型」阶段
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        // 这个回调一返回，系统就会删掉 location 处的临时文件，必须当场搬走。
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? -1
        try? FileManager.default.removeItem(at: stagingURL)
        do {
            try FileManager.default.moveItem(at: location, to: stagingURL)
            let attrs = try? FileManager.default.attributesOfItem(atPath: stagingURL.path)
            let size = (attrs?[.size] as? Int64) ?? downloadTask.countOfBytesReceived
            finish(.success((status: status, bytes: size)))
        } catch {
            finish(.failure(error))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error { finish(.failure(error)) }
    }

    // MARK: - 内部

    private func finish(_ result: Result<(status: Int, bytes: Int64), Error>) {
        guard !finished else { return }
        finished = true
        continuation?.resume(with: result)
        continuation = nil
    }
}
