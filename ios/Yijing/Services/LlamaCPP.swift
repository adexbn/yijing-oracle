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
    // 待落盘的缓冲。llama.cpp 加载模型时会逐行抛出数百条日志，
    // 若每行都 seekToEnd + write（两次系统调用）就要写几百次；
    // 这里先攒在内存，攒够 32KB 或调用 flush() 时一次性写入。
    private static var pending = ""

    /// 缓冲写：开销可忽略，可用于高频调用（如 llama.cpp 的日志回调）。
    static func log(_ message: String) {
        ioQueue.sync {
            pending += ts.string(from: Date()) + "  " + message + "\n"
            if pending.utf8.count >= 32 * 1024 { flushLocked() }
        }
    }

    /// 立即落盘。只用于「崩溃前必须留下这一行」的场合（如 GGML_ASSERT 断言回调）。
    static func logSync(_ message: String) {
        ioQueue.sync {
            pending += ts.string(from: Date()) + "  " + message + "\n"
            flushLocked()
        }
    }

    /// 把缓冲区里的日志一次性写进文件。
    static func flush() {
        ioQueue.sync { flushLocked() }
    }

    /// 真正的写盘动作（只能在 ioQueue 上调用）。日志文件可能很大，不复用旧内容。
    private static func flushLocked() {
        guard !pending.isEmpty, let data = pending.data(using: .utf8) else { return }
        if handle == nil {
            if !FileManager.default.fileExists(atPath: logURL.path) {
                FileManager.default.createFile(atPath: logURL.path, contents: nil)
            }
            handle = try? FileHandle(forWritingTo: logURL)
        }
        guard let h = handle else { return }
        try? h.seekToEnd()
        try? h.write(contentsOf: data)
        pending = ""
    }

    /// 每次 App 启动记录一次，附上版本/构建号，便于确认安装的是哪个包。
    /// 注意：构建号来自 Info.plist 的 CFBundleVersion；本项目用 XcodeGen 生成 Info.plist，
    /// 若 project.yml 的 info.properties 里没显式写这两个键，XcodeGen 会填默认值 "1.0"/"1"，
    /// 导致无论编译多少次都显示 build=1（已在 project.yml 中显式绑定到 $(MARKETING_VERSION)/$(CURRENT_PROJECT_VERSION)）。
    static func startSession() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        log("========== App 启动 version=\(version) build=\(build) ==========")
        log("日志文件路径: \(logURL.path)")
        flush()
    }

    /// 读取日志内容（供 App 内「查看日志」用）。日志可能较大，只返回末尾部分。
    static func readLog() -> String {
        flush()
        return ioQueue.sync {
            guard let data = try? Data(contentsOf: logURL) else {
                return "尚未生成日志文件。请先复现一次崩溃，再回来查看。\n文件路径：\(logURL.path)"
            }
            let full = String(data: data, encoding: .utf8) ?? "(日志编码异常)"
            if full.count > 8000 {
                return "(日志较长，仅显示末尾 8000 字符)\n\n" + String(full.suffix(8000))
            }
            return full
        }
    }
}

/// llama.cpp 薄封装：加载 GGUF 模型并做采样式对话补全。
///
/// 依赖 llama.cpp 的 iOS XCFramework（见 project.yml 的本地包 llama-ios）。
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

/// ggml 致命错误回调：GGML_ASSERT 断言失败时，ggml_abort 默认只 fprintf(stderr)，并不走
/// llama_log_set 的日志回调（这正是之前崩溃日志里看不到断言原文的原因）。用
/// ggml_set_abort_callback 把致命错误也接到文件日志，下次再崩就能看到类似
/// "llama-context.cpp:1722: GGML_ASSERT(n_tokens_all <= cparams.n_batch) failed" 的原文。
/// 必须是顶层函数（不能捕获上下文），才能被转换成 C 函数指针传给 ggml_set_abort_callback。
private func ggmlAbortCallback(_ errorMessage: UnsafePointer<CChar>?) {
    let msg = errorMessage.map { String(cString: $0) } ?? "(空消息)"
    // 这里必须用 logSync：断言随时可能把进程 abort 掉，缓冲里的内容会丢。
    YjLog.logSync("[GGML_ABORT] " + msg)
}

/// 后端与模型的常驻缓存。
///
/// 之所以要缓存：`llama_backend_init()` 会初始化 Metal 后端并加载/编译着色器库，
/// 实测在真机上要花 **15 秒**（日志里的 `compiled 'fa' library in 15.137 sec`）；
/// 模型加载还要再读 1.2GB 权重。这两步每次解卦都重做一次纯属浪费，
/// 缓存后只在 App 生命周期内做一次，单次解卦耗时能少掉十几秒。
/// context 仍然每次新建（只要 0.1 秒左右），避免复用 KV cache 带来的位置/状态问题。
private enum LlamaRuntime {
    private static let lock = NSLock()
    private static var backendInited = false
    private static var loadedPath: String?
    private static var loadedModel: OpaquePointer?
    private static var loadedVocab: OpaquePointer?

    /// 初始化后端（只做一次；此处不调用 llama_backend_free，让它活到进程结束）。
    static func ensureBackend() {
        lock.lock()
        defer { lock.unlock() }
        guard !backendInited else {
            YjLog.log("STEP 1: 后端已初始化，跳过（省去 Metal 着色器库加载）")
            return
        }
        YjLog.log("STEP 1: llama_backend_init 前（首次，含 Metal 库加载，可能约 15 秒）")
        llama_backend_init()
        backendInited = true
        YjLog.log("STEP 1: llama_backend_init OK")
    }

    /// 取模型与 vocab，命中缓存就直接复用。
    static func loadModel(path: String) throws -> (model: OpaquePointer, vocab: OpaquePointer) {
        lock.lock()
        defer { lock.unlock() }

        if loadedPath == path, let m = loadedModel, let v = loadedVocab {
            YjLog.log("STEP 2: 复用已加载的模型与 vocab（跳过 1.2GB 加载）")
            return (m, v)
        }
        // 模型文件换了（重新下载/导入），释放旧的。
        if let old = loadedModel {
            llama_model_free(old)
        }
        loadedModel = nil
        loadedVocab = nil
        loadedPath = nil

        var mparams = llama_model_default_params()
        // 关键性能开关：把全部层卸载到 Metal（A19 GPU）上跑。
        // 之前设 0（纯 CPU）是怀疑 Metal offload 致崩，但后来查明崩溃真因是
        // prompt 一次性超过 n_batch 触发 GGML_ASSERT（已修复），与 GPU 无关。
        // 纯 CPU 时 1.7B 模型实测只有约 19 token/s，iPhone 17 的 GPU 全程闲置；
        // 开启 GPU 卸载后同一模型通常快 2~4 倍。
        mparams.n_gpu_layers = 99
        // 禁用 mmap：不完整/损坏的模型文件用 mmap 加载时，访问越界会触发 SIGBUS 直接崩溃。
        // 改用普通读取后，文件问题会返回 nil（可捕获为「模型加载失败」），而非闪退。
        mparams.load_mode = LLAMA_LOAD_MODE_NONE
        YjLog.log("STEP 2: 开始加载模型 (n_gpu_layers=99 走 Metal, load_mode=NONE)")
        var loaded = llama_model_load_from_file(path, mparams)
        if loaded == nil {
            // GPU 路径失败（如显存不足）时退回纯 CPU：宁可慢，也不要打不开。
            YjLog.logSync("STEP 2: GPU 卸载加载失败，回退纯 CPU 重试")
            mparams.n_gpu_layers = 0
            loaded = llama_model_load_from_file(path, mparams)
        }
        guard let m = loaded else {
            YjLog.logSync("STEP 2 失败：模型加载返回 nil")
            throw LlamaError.modelLoadFailed
        }
        // b5092 之后 token 相关接口改用 vocab 指针（而非 model 指针）。
        guard let v = llama_model_get_vocab(m) else {
            llama_model_free(m)
            YjLog.log("STEP 3 失败：获取 vocab 返回 nil")
            throw LlamaError.modelLoadFailed
        }
        loadedModel = m
        loadedVocab = v
        loadedPath = path
        YjLog.log("STEP 2: 模型加载 OK")
        return (m, v)
    }
}

enum LlamaCPP {

    /// 是否保留 Qwen3 的思考段。本版本为 false（non-thinking 模式）：
    /// 模板补空思考块，模型直接作答，速度更快且不会出现思考段跑飞的问题。
    ///
    /// 与 Android 端 `LocalAiClient.ENABLE_THINKING` 同步。
    static let enableThinking = false

    /// 生成一次回复。并发调用会被串行化（同一时刻只跑一次推理）。
    ///
    /// maxTokens 默认 200：non-thinking 模式下不需要给思考段留预算，
    /// 正常答案约 100 字 / ~130 token，200 足够。
    /// 与 Android 端 `LocalAiClient.MAX_TOKENS_THINKING` 取齐。
    static func complete(modelPath: String, system: String, user: String, maxTokens: Int32 = 200) throws -> String {
        // 先把 llama.cpp 的日志接到文件日志，加载失败时能拿到底层原因。
        llama_log_set(llamaLogCallback, nil)
        // GGML_ASSERT 断言失败时走 ggml_abort，默认只 fprintf(stderr) 不进 llama 日志回调；
        // 注册 abort 回调把它也落到文件日志，崩溃时能看到断言原文（如 n_batch 越界）。
        ggml_set_abort_callback(ggmlAbortCallback)

        let startedAt = Date()
        var phaseAt = startedAt
        /// 记录上一阶段耗时，并在日志里打一条「⏱」行 —— 事后看日志即可知道时间花在哪一步。
        func phaseDone(_ name: String, extra: String = "") {
            let now = Date()
            let ms = Int(now.timeIntervalSince(phaseAt) * 1000)
            phaseAt = now
            YjLog.log("⏱ \(name)：\(ms) ms" + (extra.isEmpty ? "" : "（\(extra)）"))
        }

        YjLog.log("========== complete() 开始 ==========")
        YjLog.log("modelPath=\(modelPath)")

        if let attrs = try? FileManager.default.attributesOfItem(atPath: modelPath),
           let size = attrs[.size] as? NSNumber {
            YjLog.log("模型文件大小 = \(size.int64Value) bytes")
        } else {
            YjLog.log("警告：无法读取模型文件属性")
        }
        phaseDone("读取模型文件属性")

        LlamaRuntime.ensureBackend()
        phaseDone("后端初始化（首次含 Metal 着色器库编译）")
        let (model, vocab) = try LlamaRuntime.loadModel(path: modelPath)
        phaseDone("模型加载")

        // 内存优化：Qwen3-1.7B 的 KV cache 较大（8 个 KV head），n_ctx=4096 会占用约 450MB KV cache，
        // 加上 1.2GB 模型权重容易触发 iOS Jetsam 闪退。解卦场景 prompt+输出通常 < 1000 token，
        // 降到 2048 可让 KV cache 减半；n_batch 同步调小以降低峰值内存。
        var cparams = llama_context_default_params()
        cparams.n_ctx = 2048
        cparams.n_batch = 256
        // 之前用 activeProcessorCount/2（=3）线程，CPU 推理被白白拖慢一半；
        // 这里用满全部核心（A19 为 6 核）。
        let threads = Int32(min(8, max(1, ProcessInfo.processInfo.activeProcessorCount)))
        cparams.n_threads = threads
        cparams.n_threads_batch = threads
        YjLog.log("STEP 4: 创建 context (n_ctx=2048, n_batch=256, threads=\(threads))")
        guard let ctx = llama_init_from_model(model, cparams) else {
            YjLog.log("STEP 4 失败：context 创建返回 nil")
            throw LlamaError.contextFailed
        }
        defer { llama_free(ctx) }
        YjLog.log("STEP 4: context OK")
        phaseDone("创建 context")
        _ = model

        // 关键：必须套上模型的对话模板，否则模型不处于「对话模式」。见 applyChatTemplate 注释。
        let prompt = applyChatTemplate(system: system, user: user)
        YjLog.log("STEP 5: 开始分词 (prompt \(prompt.utf8.count) 字节)")
        let tokens = try tokenize(vocab, text: prompt)
        if tokens.isEmpty { throw LlamaError.tokenizeFailed }
        YjLog.log("STEP 5: 分词 OK, tokens=\(tokens.count)（含 ChatML 标记与 assistant 引导）")
        // 留一条能一眼验证「到底思考没思考」的日志：模板不补空思考块才是开启思考。
        YjLog.log("STEP 5: 思考模式 = " + (enableThinking ? "开启（模板不补空思考块，模型会自己开 <think> 段）" : "关闭（模板补了空思考块）"))
        phaseDone("分词", extra: "\(tokens.count) tokens")

        let eos = llama_vocab_eos(vocab)
        // 采样链：top_k 20 → top_p → temp → dist。Qwen 官方给的推荐值是**成套**的，必须跟着思考模式走：
        //   思考模式：Temperature=0.6, TopP=0.95, TopK=20, MinP=0
        //   非思考模式：Temperature=0.7, TopP=0.8, TopK=20, MinP=0
        // 用错套会实打实变差：思考段用 0.7/0.8 会啰嗦发散，答案用 0.6/0.95 容易跑题。
        // 官方同时明确「不要用贪心解码」，小模型会陷入复读退化（旧日志里连续 1024 个「！」就是这么来的）。
        let temperature: Float = enableThinking ? 0.6 : 0.7
        let topP: Float = enableThinking ? 0.95 : 0.8
        let seed = UInt32.random(in: 1...UInt32.max)
        let sparams = llama_sampler_chain_default_params()
        guard let smpl = llama_sampler_chain_init(sparams) else {
            YjLog.log("STEP 5 失败：采样器创建返回 nil")
            throw LlamaError.contextFailed
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20))
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1))
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature))
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed))
        defer { llama_sampler_free(smpl) }
        YjLog.log("STEP 5: 采样参数 top_k=20, top_p=\(topP), temp=\(temperature), maxTokens=\(maxTokens), seed=\(seed)")

        // 分批喂入 prompt：llama_decode 单次最多处理 n_batch（本例 256）个 token。
        // 一次性全量喂入会触发 llama-context.cpp 的
        // GGML_ASSERT(n_tokens_all <= cparams.n_batch) 直接 abort
        //（正是之前「STEP 6: 首次 llama_decode 前」之后无任何日志、静默崩溃的根因）。
        // 每批 <= n_batch 切分逐批 decode；llama_batch_get_one 的 pos 为 NULL，
        // llama_decode 会自动递增 token 位置，跨批正确衔接。
        let nBatch = Int(cparams.n_batch)
        var promptPos = 0
        while promptPos < tokens.count {
            let chunkCount = min(nBatch, tokens.count - promptPos)
            var decodeResult: Int32 = -1
            tokens.withUnsafeBufferPointer { buf in
                guard let base = buf.baseAddress else { return }
                let ptr = UnsafeMutablePointer(mutating: base.advanced(by: promptPos))
                let batch = llama_batch_get_one(ptr, Int32(chunkCount))
                decodeResult = llama_decode(ctx, batch)
            }
            YjLog.log("STEP 6: decode prompt 第 \(promptPos / nBatch + 1) 批（\(chunkCount) tokens）")
            if decodeResult != 0 {
                YjLog.log("STEP 6 失败：prompt 分批 decode 返回非 0（已处理 \(promptPos)/\(tokens.count) tokens）")
                throw LlamaError.contextFailed
            }
            promptPos += chunkCount
        }
        YjLog.log("STEP 6: prompt decode OK（共 \(tokens.count) tokens）")
        phaseDone("prompt 解码", extra: "\(tokens.count) tokens")

        var decoded = Data()
        var piece = [CChar](repeating: 0, count: 256)
        var generated = 0
        var firstTokenIDs: [llama_token] = []
        var stoppedByEOS = false

        for _ in 0..<maxTokens {
            // idx 用 -1：llama.h 官方写法，取「本批最后一个 token 的 logits」。
            // 之前用 batch.n_tokens - 1，依赖输出行映射，属于易错的写法。
            let token = llama_sampler_sample(smpl, ctx, -1)
            // 用 is_eog 判断而不是只比 eos：该 GGUF 把 </s>、<|endoftext|>、<|im_end|> 等都标成了 EOG，
            // 只比 151645 会漏掉其它终止符，白跑到 maxTokens 上限（旧日志里输出 1024 个「！」就是这么来的）。
            if llama_vocab_is_eog(vocab, token) {
                stoppedByEOS = true
                YjLog.log("STEP 7: 命中 EOG token \(token)（eos=\(eos)）")
                break
            }
            llama_sampler_accept(smpl, token)

            let n = piece.withUnsafeMutableBufferPointer { bufPtr -> Int32 in
                llama_token_to_piece(vocab, token, bufPtr.baseAddress, Int32(bufPtr.count), 0, true)
            }
            if n > 0 {
                piece.withUnsafeBufferPointer { bufPtr in
                    decoded.append(UnsafeRawPointer(bufPtr.baseAddress!).assumingMemoryBound(to: UInt8.self), count: Int(n))
                }
            }
            if firstTokenIDs.count < 10 {
                firstTokenIDs.append(token)
            }

            // 单 token 的 batch 必须活到 decode 结束，因此把 decode 放进闭包内执行。
            var next: [llama_token] = [token]
            var decodeResult: Int32 = -1
            next.withUnsafeBufferPointer { buf in
                guard let base = buf.baseAddress else { return }
                let batch = llama_batch_get_one(UnsafeMutablePointer(mutating: base), 1)
                decodeResult = llama_decode(ctx, batch)
            }
            if decodeResult != 0 {
                YjLog.log("STEP 7 中断：第 \(generated) 个 token decode 返回非 0")
                break
            }
            generated += 1
            if generated % 64 == 0 {
                YjLog.log("STEP 7: 已生成 \(generated) 个 token")
            }
        }

        // 生成速度（token/s）是判断到底走没走 GPU 的最直观指标：
        // 纯 CPU 约 19 token/s，开了 Metal 卸载会明显高于这个数。
        let genSeconds = Date().timeIntervalSince(phaseAt)
        let tps = genSeconds > 0 ? Double(generated) / genSeconds : 0
        phaseDone("生成", extra: "\(generated) tokens，\(String(format: "%.1f", tps)) token/s")

        let elapsed = String(format: "%.1f", Date().timeIntervalSince(startedAt))
        let reason = stoppedByEOS ? "命中结束符（EOG）正常结束" : "达到 maxTokens=\(maxTokens) 上限（异常：模型没吐结束符）"
        YjLog.log("STEP 8: 生成结束，共 \(generated) 个 token，输出 \(decoded.count) 字节，\(reason)，总耗时 \(elapsed) 秒")
        YjLog.log("STEP 8: 前 10 个 token id = \(firstTokenIDs)")

        // 容错解码：万一在中文多字节字符中间被截断，String(data:encoding:) 会整体返回 nil
        //（表现为「输出空白」），用 String(decoding:) 只会把非法字节替换成 U+FFFD。
        let text = String(decoding: decoded, as: UTF8.self)
        YjLog.log("STEP 8: 输出预览 = " + String(text.prefix(200)).replacingOccurrences(of: "\n", with: "⏎"))
        YjLog.flush()
        return text
    }

    /// App 启动后在后台预热：只把后端初始化一次。
    /// `llama_backend_init()` 会加载并编译 Metal 着色器库，真机实测约 15 秒；
    /// 放在启动时预热，用户点「解卦」时就不用再等这 15 秒。
    static func warmUp() {
        LlamaRuntime.ensureBackend()
    }

    /// 把模型也读进内存（只加载，不推理）。
    /// 首次初始化下载完成后调用：用户第一次点「起卦」时就直接复用已加载的模型，
    /// 不用再等 1.2GB 读盘。
    static func preloadModel() {
        guard ModelManager.isDownloaded() else { return }
        LlamaRuntime.ensureBackend()
        _ = try? LlamaRuntime.loadModel(path: ModelManager.modelFileURL().path)
    }

    /// 手工套用 Qwen3 的 ChatML 对话模板（模型自带的 tokenizer.chat_template 没有可用的 C API，
    /// 这里按模板原文拼出来，并用 parse_special 让 <|im_start|> 等标记解析成真正的特殊 token）。
    ///
    /// 为什么必须这么做：不套模板直接喂「system + 用户问题」的纯文本，模型不会把自己当成助手，
    /// 而是顺着这段文字当文章续写 —— 既不会输出 <|im_end|>（导致永远不触发 EOS，一直生成到上限），
    /// 又极易退化成一串「！」之类无意义字符（实测连续 1024 个 token 全是感叹号）。
    ///
    /// `<|im_start|>assistant` 后面要不要补一个「空的思考块」，直接决定模型思不思考 ——
    /// 这就是 Qwen3 tokenizer_config.json 里 chat_template 的**唯一分支**：
    ///     {{- '<|im_start|>assistant\n' }}
    ///     {%- if enable_thinking is defined and enable_thinking is false %}
    ///         {{- '<think>\n\n</think>\n\n' }}
    ///     {%- endif %}
    /// 补上（等价 enable_thinking=false）：开头已经是「结束了的空思考块」，模型直接作答。
    /// 不补（等价 enable_thinking=true）：模型自己开 <think> 段，权衡完再答。
    ///
    /// 本项目当前走**关闭思考**（`enableThinking = false`）：
    /// Qwen3.5-2B 尺寸下思考模式不稳定，容易跑飞进英文 Thinking Process 不出结束符，
    /// non-thinking 模式速度更快、输出更可控。
    /// 注意「只写 `<think>` 不写 `</think>`」是错的：那会把模型关在思考块里无尽写内心戏，
    /// 永远等不到正文。要开就整个不补，要关就整块补全。
    private static func applyChatTemplate(system: String, user: String) -> String {
        var p = ""
        p += "<|im_start|>system\n" + system + "<|im_end|>\n"
        p += "<|im_start|>user\n" + user + "<|im_end|>\n"
        p += "<|im_start|>assistant\n"
        if !enableThinking {
            p += "<think>\n\n</think>\n\n"
        }
        return p
    }

    private static func tokenize(_ vocab: OpaquePointer, text: String) throws -> [llama_token] {
        let byteLen = text.utf8.count
        var buffer = [llama_token](repeating: 0, count: 8192)
        // 第 6 个参数 parse_special 必须为 true，否则 "<|im_start|>" 会被当成普通文本切碎。
        let n = buffer.withUnsafeMutableBufferPointer { bufPtr in
            text.withCString { cPtr in
                llama_tokenize(vocab, cPtr, Int32(byteLen), bufPtr.baseAddress, Int32(bufPtr.count), true, true)
            }
        }
        guard n > 0 else {
            YjLog.log("STEP 5 失败：llama_tokenize 返回 \(n)（负数表示缓冲区不足）")
            throw LlamaError.tokenizeFailed
        }
        return Array(buffer.prefix(Int(n)))
    }
}
