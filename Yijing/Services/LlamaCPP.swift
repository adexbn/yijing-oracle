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

enum LlamaCPP {

    static func complete(modelPath: String, system: String, user: String, maxTokens: Int32 = 1024) throws -> String {
        llama_backend_init()
        defer { llama_backend_free() }

        var mparams = llama_model_default_params()
        mparams.n_gpu_layers = 99
        guard let model = llama_model_load_from_file(modelPath, mparams) else {
            throw LlamaError.modelLoadFailed
        }
        defer { llama_model_free(model) }

        var cparams = llama_context_default_params()
        cparams.n_ctx = 4096
        cparams.n_batch = 512
        let threads = Int32(max(1, ProcessInfo.processInfo.activeProcessorCount / 2))
        cparams.n_threads = threads
        cparams.n_threads_batch = threads

        guard let ctx = llama_new_context_with_model(model, cparams) else {
            throw LlamaError.contextFailed
        }
        defer { llama_free(ctx) }

        let prompt = system + "\n\n" + user
        let tokens = try tokenize(model, text: prompt)
        if tokens.isEmpty { throw LlamaError.tokenizeFailed }

        let eos = llama_token_eos(model)
        let smpl = llama_sampler_init_greedy(llama_default_seed())
        defer { llama_sampler_free(smpl) }

        var batch = tokens.withUnsafeBufferPointer { buf in
            llama_batch_get_one(UnsafeMutablePointer(mutating: buf.baseAddress), Int32(buf.count))
        }
        if llama_decode(ctx, batch) != 0 {
            throw LlamaError.contextFailed
        }

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