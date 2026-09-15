import Foundation
import llama

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

/// llama.cpp 日志回调：把底层日志接到 NSLog，便于通过爱思助手实时日志查看加载失败的真实原因。
/// 必须是顶层函数（不能捕获上下文），才能被转换成 C 函数指针传给 llama_log_set。
private func llamaLogCallback(level: ggml_log_level, text: UnsafePointer<CChar>?, userData: UnsafeMutableRawPointer?) {
    guard let text = text else { return }
    let message = String(cString: text).trimmingCharacters(in: .whitespacesAndNewlines)
    guard !message.isEmpty else { return }
    NSLog("[llama] %@", message)
}

enum LlamaCPP {

    static func complete(modelPath: String, system: String, user: String, maxTokens: Int32 = 1024) throws -> String {
        // 先把 llama.cpp 的日志接到 NSLog，加载失败时能拿到底层原因。
        llama_log_set(llamaLogCallback, nil)

        if let attrs = try? FileManager.default.attributesOfItem(atPath: modelPath),
           let size = attrs[.size] as? NSNumber {
            NSLog("[yijing] loading model: %@ (%@ bytes)", modelPath, size)
        }

        llama_backend_init()
        defer { llama_backend_free() }

        var mparams = llama_model_default_params()
        mparams.n_gpu_layers = 99
        guard let model = llama_load_model_from_file(modelPath, mparams) else {
            throw LlamaError.modelLoadFailed
        }
        defer { llama_free_model(model) }
        NSLog("[yijing] model loaded OK")

        // 内存优化：Qwen3-1.7B 的 KV cache 较大（8 个 KV head），n_ctx=4096 会占用约 450MB KV cache，
        // 加上 1.2GB 模型权重容易触发 iOS Jetsam 闪退。解卦场景 prompt+输出通常 < 1000 token，
        // 降到 2048 可让 KV cache 减半；n_batch 同步调小以降低峰值内存。
        var cparams = llama_context_default_params()
        cparams.n_ctx = 2048
        cparams.n_batch = 256
        let threads = Int32(max(1, ProcessInfo.processInfo.activeProcessorCount / 2))
        cparams.n_threads = threads
        cparams.n_threads_batch = threads

        guard let ctx = llama_new_context_with_model(model, cparams) else {
            throw LlamaError.contextFailed
        }
        defer { llama_free(ctx) }
        NSLog("[yijing] context created: n_ctx=%d n_batch=%d n_gpu_layers=%d", cparams.n_ctx, cparams.n_batch, mparams.n_gpu_layers)

        let prompt = system + "\n\n" + user
        let tokens = try tokenize(model, text: prompt)
        if tokens.isEmpty { throw LlamaError.tokenizeFailed }

        let eos = llama_token_eos(model)
        let smpl = llama_sampler_init_greedy()
        defer { llama_sampler_free(smpl) }

        var batch = tokens.withUnsafeBufferPointer { buf in
            llama_batch_get_one(UnsafeMutablePointer(mutating: buf.baseAddress), Int32(buf.count))
        }
        if llama_decode(ctx, batch) != 0 {
            throw LlamaError.contextFailed
        }
        NSLog("[yijing] first decode OK, tokens=%d", tokens.count)

        var decoded = Data()
        var piece = [CChar](repeating: 0, count: 256)
        for _ in 0..<maxTokens {
            let token = llama_sampler_sample(smpl, ctx, batch.n_tokens - 1)
            llama_sampler_accept(smpl, token)
            if token == eos { break }

            let n = piece.withUnsafeMutableBufferPointer { bufPtr -> Int32 in
                llama_token_to_piece(model, token, bufPtr.baseAddress, Int32(bufPtr.count), 0, true)
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
            if llama_decode(ctx, batch) != 0 { break }
        }

        return String(data: decoded, encoding: .utf8) ?? ""
    }

    private static func tokenize(_ model: OpaquePointer, text: String) throws -> [llama_token] {
        let byteLen = text.utf8.count
        var buffer = [llama_token](repeating: 0, count: 4096)
        let n = buffer.withUnsafeMutableBufferPointer { bufPtr in
            text.withCString { cPtr in
                llama_tokenize(model, cPtr, Int32(byteLen), bufPtr.baseAddress, Int32(bufPtr.count), true, false)
            }
        }
        guard n > 0 else { throw LlamaError.tokenizeFailed }
        return Array(buffer.prefix(Int(n)))
    }
}