import Foundation

/// 本地小模型（GGUF）的下载与文件管理。模型不随 App 打包，安装后按需下载或本地导入。
enum ModelManager {

    static let modelFile = "qwen3-1.7b-q4_k_m.gguf"
    static let modelURL = "https://huggingface.co/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
    static let modelURLMirror = "https://hf-mirror.com/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"

    /// 下载源顺序：国内镜像优先，原版兜底。
    static let sources = [modelURLMirror, modelURL]

    private static var modelsDir: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("models", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func modelFileURL() -> URL {
        modelsDir.appendingPathComponent(modelFile)
    }

    static func isDownloaded() -> Bool {
        let f = modelFileURL()
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: f.path),
              let size = attrs[.size] as? Int64 else { return false }
        return size > 100 * 1024 * 1024
    }

    static func delete() {
        let f = modelFileURL()
        try? FileManager.default.removeItem(at: f)
        try? FileManager.default.removeItem(at: f.appendingPathExtension("part"))
    }

    /// 从本地流导入模型文件。
    static func importFrom(_ input: Data) throws -> URL {
        let dest = modelFileURL()
        try input.write(to: dest)
        return dest
    }

    /// 依次尝试所有源下载，通过 [onProgress] 回调 0..100。先写 .part，成功后重命名。
    static func download(onProgress: @escaping (Int) -> Void) async throws -> URL {
        var lastError: Error?
        for url in sources {
            do {
                return try await downloadFrom(url, onProgress: onProgress)
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

        var req = URLRequest(url: url)
        req.setValue("Mozilla/5.0 (iPhone) yijing-app", forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 60

        let (bytes, resp) = try await URLSession.shared.bytes(for: req)
        guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw ApiError("下载失败 HTTP \((resp as? HTTPURLResponse)?.statusCode ?? -1)")
        }

        let total = http.expectedContentLength
        try FileManager.default.createDirectory(at: tmp.deletingLastPathComponent(), withIntermediateDirectories: true)
        FileManager.default.createFile(atPath: tmp.path, contents: nil)
        let handle = try FileHandle(forWritingTo: tmp)
        defer { try? handle.close() }

        var copied: Int64 = 0
        for try await byte in bytes {
            handle.write(byte)
            copied += 1
            if total > 0 {
                onProgress(Int(Double(copied) / Double(total) * 100))
            }
        }
        try handle.close()

        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)
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