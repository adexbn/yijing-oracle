import Foundation
import llama

/// 文件日志：把关键步骤与 llama.cpp 底层日志写到 App 的 Documents/yijing_log.txt。
/// 实时控制台日志会被 iOS 隐私机制把动态内容遮蔽成 <private>，且崩溃瞬间可能丢失；
/// 写文件则崩溃后仍在，可通过爱思助手「文件管理」导出该文件查看（本文件会保留历史，多次运行以 ==== 分隔）。
enum YjLog {
    private static let logURL: URL = {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("yijing_log.txt")
    }()
    private static let ioQueue = DispatchQueue(label: "com.yijing.log.io")
    private static let ts: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()
    // 保持句柄常开，避免每次写都 open/close（llama.cpp 加载时会打印大量日志行）。
    private static var handle: FileHandle?

    static func log(_ message: String) {
        // 同步写盘：确保崩溃前一步的日志已经落到文件里（而不是留在内存队列中丢失）。
        ioQueue.sync {
            let line = ts.string(from: Date()) + "  " + message + "\n"
            guard let data = line.data(using: .utf8) else { return }
            if handle == nil {
                if !FileManager.default.fileExists(atPath: logURL.path) {
                    FileManager.default.createFile(atPath: logURL.path, contents: nil)
                }
                handle = try? FileHandle(forWritingTo: logURL)
            }
            guard let h = handle else { return }
            try? h.seekToEnd()
            try? h.write(contentsOf: data)
        }
    }

    /// 每次 App 启动记录一次，附上版本/构建号，便于确认安装的是哪个包。
    static func startSession() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        log("========== App 启动 version=\(version) build=\(build) ==========")
        log("日志文件路径: \(logURL.path)")
    }
}

/// llama.cpp 薄封装：加载 GGUF 模型并做贪心采样式补全。
///
/// 依赖 ggml-org/llama.cpp 的 Swift Package（见 project.yml）。
/// llama.cpp 的 C API 在版本间偶有字段/函数名调整；若编译报错，
/// 请对照所安装版本的 `llama.h` 校正参数名（多集中于 context/model 参数与采样器）。
enum LlamaError: LocalizedError {
    case modelLoadFailed
    case contextFailed
    case tokenizeFailed
    var errorDescription: String? {
        switch self {
        case .modelLoadFailed: return "本地模型加载失败"
        case .contextFailed: return "本地模型初始化失败"
        case .tokenizeFailed: return "本地模型分词失败"
        }
    }
}

/// llama.cpp 日志回调：把底层日志接到文件日志，崩溃前最后的报错（如 GGML_ASSERT）会原样落盘。
/// 必须是顶层函数（不能捕获上下文），才能被转换成 C 函数指针传给 llama_log_set。
private func llamaLogCallback(level: ggml_log_level, text: UnsafePointer<CChar>?, userData: UnsafeMutableRawPointer?) {
    guard let text = text else { return }
    let message = String(cString: text).trimmingCharacters(in: .whitespacesAndNewlines)
    guard !message.isEmpty else { return }
    YjLog.log("[llama] " + message)
}

enum LlamaCPP {

    static func complete(modelPath: String, system: String, user: String, maxTokens: Int32 = 1024) throws -> String {
        // 先把 llama.cpp 的日志接到文件日志，加载失败时能拿到底层原因。
        llama_log_set(llamaLogCallback, nil)
        YjLog.log("========== complete() 开始 ==========")
        YjLog.log("modelPath=\(modelPath)")

        if let attrs = try? FileManager.default.attributesOfItem(atPath: modelPath),
           let size = attrs[.size] as? NSNumber {
            YjLog.log("模型文件大小 = \(size.int64Value) bytes")
        } else {
            YjLog.log("警告：无法读取模型文件属性")
        }

        YjLog.log("STEP 1: llama_backend_init 前")
        llama_backend_init()
        defer { llama_backend_free() }
        YjLog.log("STEP 1: llama_backend_init OK")

        var mparams = llama_model_default_params()
        // 用纯 CPU 推理：b5092 崩 SIGBUS、b10809 崩 SIGABRT，两次都崩在 Metal GPU 后端
        //（日志里有 16 秒 Metal 着色器编译 + Metal Warning 后直接 abort）。
        // 说明该机型上 Metal offload 不稳定，改用 n_gpu_layers=0 让所有层走 CPU 后端，
        // 更稳定，代价是推理变慢。
        mparams.n_gpu_layers = 0
        // 禁用 mmap：不完整/损坏的模型文件用 mmap 加载时，访问越界会触发 SIGBUS 直接崩溃。
        // 改用普通读取后，文件问题会返回 nil（可捕获为「模型加载失败」），而非闪退。
        // b5092 及以后版本用 load_mode 取代 use_mmap 字段；NONE = 不启用 mmap。
        mparams.load_mode = LLAMA_LOAD_MODE_NONE
        YjLog.log("STEP 2: 开始加载模型 (n_gpu_layers=0, load_mode=NONE)")
        guard let model = llama_model_load_from_file(modelPath, mparams) else {
            YjLog.log("STEP 2 失败：模型加载返回 nil")
            throw LlamaError.modelLoadFailed
        }
        defer { llama_model_free(model) }
        YjLog.log("STEP 2: 模型加载 OK")

        // b5092 之后 token 相关接口改用 vocab 指针（而非 model 指针）。
        guard let vocab = llama_model_get_vocab(model) else {
            YjLog.log("STEP 3 失败：获取 vocab 返回 nil")
            throw LlamaError.modelLoadFailed
        }
        YjLog.log("STEP 3: vocab OK")

        // 内存优化：Qwen3-1.7B 的 KV cache 较大（8 个 KV head），n_ctx=4096 会占用约 450MB KV cache，
        // 加上 1.2GB 模型权重容易触发 iOS Jetsam 闪退。解卦场景 prompt+输出通常 < 1000 token，
        // 降到 2048 可让 KV cache 减半；n_batch 同步调小以降低峰值内存。
        var cparams = llama_context_default_params()
        cparams.n_ctx = 2048
        cparams.n_batch = 256
        let threads = Int32(max(1, ProcessInfo.processInfo.activeProcessorCount / 2))
        cparams.n_threads = threads
        cparams.n_threads_batch = threads
        YjLog.log("STEP 4: 创建 context (n_ctx=2048, n_batch=256, threads=\(threads))")
        guard let ctx = llama_init_from_model(model, cparams) else {
            YjLog.log("STEP 4 失败：context 创建返回 nil")
            throw LlamaError.contextFailed
        }
        defer { llama_free(ctx) }
        YjLog.log("STEP 4: context OK")

        let prompt = system + "\n\n" + user
        YjLog.log("STEP 5: 开始分词 (prompt \(prompt.utf8.count) 字节)")
        let tokens = try tokenize(vocab, text: prompt)
        if tokens.isEmpty { throw LlamaError.tokenizeFailed }
        YjLog.log("STEP 5: 分词 OK, tokens=\(tokens.count)")

        let eos = llama_vocab_eos(vocab)
        let smpl = llama_sampler_init_greedy()
        defer { llama_sampler_free(smpl) }

        var batch = tokens.withUnsafeBufferPointer { buf in
            llama_batch_get_one(UnsafeMutablePointer(mutating: buf.baseAddress), Int32(buf.count))
        }
        YjLog.log("STEP 6: 首次 llama_decode 前")
        if llama_decode(ctx, batch) != 0 {
            YjLog.log("STEP 6 失败：首次 decode 返回非 0")
            throw LlamaError.contextFailed
        }
        YjLog.log("STEP 6: 首次 decode OK")

        var decoded = Data()
        var piece = [CChar](repeating: 0, count: 256)
        var generated = 0
        for _ in 0..<maxTokens {
            let token = llama_sampler_sample(smpl, ctx, batch.n_tokens - 1)
            llama_sampler_accept(smpl, token)
            if token == eos { break }

            let n = piece.withUnsafeMutableBufferPointer { bufPtr -> Int32 in
                llama_token_to_piece(vocab, token, bufPtr.baseAddress, Int32(bufPtr.count), 0, true)
            }
            if n > 0 {
                piece.withUnsafeBufferPointer { bufPtr in
                    decoded.append(UnsafeRawPointer(bufPtr.baseAddress!).assumingMemoryBound(to: UInt8.self), count: Int(n))
                }
            }

            var next = [token]
            batch = next.withUnsafeBufferPointer { buf in
                llama_batch_get_one(UnsafeMutablePointer(mutating: buf.baseAddress), Int32(buf.count))
            }
            if llama_decode(ctx, batch) != 0 {
                YjLog.log("STEP 7 中断：第 \(generated) 个 token decode 返回非 0")
                break
            }
            if generated % 32 == 0 {
                YjLog.log("STEP 7: 已生成 \(generated) 个 token")
            }
            generated += 1
        }
        YjLog.log("STEP 8: 生成结束，共 \(generated) 个 token，输出 \(decoded.count) 字节")

        return String(data: decoded, encoding: .utf8) ?? ""
    }

    private static func tokenize(_ vocab: OpaquePointer, text: String) throws -> [llama_token] {
        let byteLen = text.utf8.count
        var buffer = [llama_token](repeating: 0, count: 4096)
        let n = buffer.withUnsafeMutableBufferPointer { bufPtr in
            text.withCString { cPtr in
                llama_tokenize(vocab, cPtr, Int32(byteLen), bufPtr.baseAddress, Int32(bufPtr.count), true, false)
            }
        }
        guard n > 0 else { throw LlamaError.tokenizeFailed }
        return Array(buffer.prefix(Int(n)))
    }
}
