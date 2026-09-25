package com.yijing.app.core

import android.util.Log
import org.json.JSONObject
import java.io.Closeable

/**
 * MNN（阿里端侧推理框架）JNI 绑定层。
 *
 * 方法与 `android/app/src/main/cpp/yijing_llm_jni.cpp` 里的 9 个
 * `Java_com_yijing_app_core_MnnLlm_*` 入口一一对应。改这里的名字或签名，
 * 必须同步改 C++，否则运行期抛 `UnsatisfiedLinkError`。
 *
 * 换掉 llama.cpp 的动机：MNN 自带 OpenCL 后端，高通 Adreno 与华为 Mali
 * 都能吃上 GPU；llama.cpp 在 Android 上只有 CPU 路径可用。
 */
object MnnLlm {

    /** 与 CMake 里 `add_library(yijingllm SHARED ...)` 对应，产物为 libyijingllm.so。 */
    private const val LIB_NAME = "yijingllm"

    /** 引擎 `LlmStatus`（见 llm.hpp），与 C++ 枚举值逐位一致。 */
    const val STATUS_NOT_LOADED = -1
    const val STATUS_RUNNING = 0
    const val STATUS_NORMAL_FINISHED = 1
    const val STATUS_MAX_TOKENS_FINISHED = 2
    const val STATUS_USER_CANCEL = 3
    const val STATUS_INTERNAL_ERROR = 4
    const val STATUS_TIMEOUT = 5

    /** create() 时写入的 `backend_type` 取值，对应 llm.cpp 的 backend_type_convert()。 */
    const val BACKEND_CPU = "cpu"
    const val BACKEND_OPENCL = "opencl"
    const val BACKEND_VULKAN = "vulkan"

    /**
     * 加载 so 的结果：null 表示成功。
     *
     * 用属性初始化而不是 init 块，是为了让 object 的类初始化仍然保持惰性
     * （首次访问才加载 so），同时把失败原因记录下来，避免每个调用点都包一层 catch。
     */
    val loadError: String? = try {
        System.loadLibrary(LIB_NAME)
        null
    } catch (t: Throwable) {
        Log.e("MnnLlm", "System.loadLibrary($LIB_NAME) 失败", t)
        t.message ?: t.javaClass.name
    }

    /** so 是否可用。false 时不要调用下面任何 native 方法。 */
    val isAvailable: Boolean get() = loadError == null

    // ------------------------------------------------------------------ native

    /** MNN 版本号，例如 "3.6.1"。 */
    external fun nativeVersion(): String

    /**
     * 建会话：createLLM(configPath) -> set_config(configJson) -> load()。
     *
     * @param configPath 模型目录下 `config.json` 的绝对路径。引擎用它的目录名做
     *                   `base_dir`，所以 `llm.mnn` / `llm.mnn.weight` / `tokenizer.txt`
     *                   必须和它在同一个目录里。
     * @param configJson 运行期配置覆盖，merge 在模型自带配置之上。
     * @return 会话句柄，理论上恒非 0（仅内存分配失败才为 0）。句柄本身不表示
     *         加载成功，必须再看 [nativeLastError]。
     */
    external fun nativeCreate(configPath: String, configJson: String): Long

    /** 最近一次失败原因；空串表示无错。 */
    external fun nativeLastError(handle: Long): String

    /** 引擎实际生效的配置（dump_config），用来确认 backend/线程/上下文真的生效了。 */
    external fun nativeDumpConfig(handle: Long): String

    /** 上次 [nativeCreate] 的模型加载耗时（毫秒）。 */
    external fun nativeLoadMillis(handle: Long): Long

    /**
     * 跑一次阻塞式补全并返回生成文本。
     *
     * @param maxTokens 传给引擎的 `max_new_tokens`，语义以 llm.cpp 的
     *                  `generate(input_ids, max_tokens)` 为准：
     *                  负数 = 用配置里的 `max_new_tokens`；0 = 只做 prefill 不出字；正数 = 最多生成这么多。
     * @param resetHistory true 时先丢掉会话 KV 与对话历史（每次解卦彼此独立）。
     */
    external fun nativeComplete(
        handle: Long,
        system: String,
        user: String,
        maxTokens: Int,
        resetHistory: Boolean
    ): String

    /**
     * 上次 [nativeComplete] 的统计，固定 10 个槽位：
     * `[0]prefillUs [1]decodeUs [2]sampleUs [3]ttfaUs [4]promptLen`
     * `[5]genSeqLen [6]allSeqLen [7]status [8]loadUs [9]wallUs`
     */
    external fun nativeLastStats(handle: Long): LongArray

    /** 清空 KV 缓存与对话历史，模型保持加载。 */
    external fun nativeReset(handle: Long)

    /** 销毁会话并释放权重。传 0 安全。 */
    external fun nativeRelease(handle: Long)

    // -------------------------------------------------------------- 配置与结果

    /**
     * 一次推理的耗时拆解，单位统一成微秒（引擎原始口径），对外暴露毫秒/速率换算。
     */
    data class MnnStats(
        val prefillUs: Long,
        val decodeUs: Long,
        val sampleUs: Long,
        val ttfaUs: Long,
        val promptLen: Int,
        val genSeqLen: Int,
        val allSeqLen: Int,
        val status: Int,
        val loadUs: Long,
        val wallUs: Long
    ) {
        val prefillMs: Long get() = prefillUs / 1000
        val decodeMs: Long get() = decodeUs / 1000
        val wallMs: Long get() = wallUs / 1000

        /**
         * 生成速率。口径：引擎自报的 `gen_seq_len` ÷ `decode_us`，
         * 只算解码段，不含 prefill。非流式调用下 [genSeqLen] 是整段生成的 token 数。
         */
        val tokensPerSecond: Float
            get() = if (decodeUs <= 0L) 0f else genSeqLen * 1_000_000f / decodeUs

        val statusName: String
            get() = when (status) {
                STATUS_NOT_LOADED -> "NOT_LOADED"
                STATUS_RUNNING -> "RUNNING"
                STATUS_NORMAL_FINISHED -> "NORMAL_FINISHED"
                STATUS_MAX_TOKENS_FINISHED -> "MAX_TOKENS_FINISHED"
                STATUS_USER_CANCEL -> "USER_CANCEL"
                STATUS_INTERNAL_ERROR -> "INTERNAL_ERROR"
                STATUS_TIMEOUT -> "TIMEOUT"
                else -> "UNKNOWN($status)"
            }

        /** 正常收尾的两种状态。 */
        val isFinished: Boolean
            get() = status == STATUS_NORMAL_FINISHED || status == STATUS_MAX_TOKENS_FINISHED

        val isError: Boolean
            get() = status == STATUS_INTERNAL_ERROR ||
                status == STATUS_TIMEOUT ||
                status == STATUS_USER_CANCEL

        companion object {
            fun from(values: LongArray?): MnnStats {
                if (values == null || values.size < 10) {
                    return MnnStats(0, 0, 0, 0, 0, 0, 0, STATUS_NOT_LOADED, 0, 0)
                }
                return MnnStats(
                    prefillUs = values[0],
                    decodeUs = values[1],
                    sampleUs = values[2],
                    ttfaUs = values[3],
                    promptLen = values[4].toInt(),
                    genSeqLen = values[5].toInt(),
                    allSeqLen = values[6].toInt(),
                    status = values[7].toInt(),
                    loadUs = values[8],
                    wallUs = values[9]
                )
            }
        }
    }

    /** 一次补全的产出：文本 + 引擎统计。 */
    data class MnnResult(val text: String, val stats: MnnStats)

    /**
     * 引擎运行期配置，序列化成 JSON 后交给 `Llm::set_config()`。
     *
     * 键名必须与 MNN `LlmConfig` 的 accessor 完全一致（见 `llmconfig.hpp`），
     * 写错了不会报错，只会被静默忽略。默认值刻意与 MNN 上游一致，便于对照排查。
     */
    data class MnnConfig(
        /** [BACKEND_CPU] / [BACKEND_OPENCL] / [BACKEND_VULKAN]。 */
        val backend: String = BACKEND_CPU,
        /** CPU 线程数。选 OpenCL 时引擎会自己加 buffer-mode 位（|64|512），这里照传 CPU 线程数即可。 */
        val threadNum: Int = 4,
        /** 上下文总长度上限。 */
        val maxAllTokens: Int = 2048,
        /** [nativeComplete] 传负数时使用的生成上限。 */
        val maxNewTokens: Int = 512,
        /** "low" = 低精度（fp16），"high" = 高精度。 */
        val precision: String = "low",
        /** "low" / "normal" / "high"，映射 BackendConfig::Power_*，只认 "low"/"high"。 */
        val power: String = "normal",
        /** "low" / "normal" / "high"，映射 BackendConfig::Memory_*。 */
        val memory: String = "low",
        /** 非 CPU 后端必填：引擎会写 `<tmpPath>/mnn_cachefile.bin`，不能给 "."（手机上不可写）。 */
        val tmpPath: String = "",
        /** true 时跨调用保留 KV（多轮对话省 prefill）。每次解卦独立，默认关。 */
        val reuseKv: Boolean = false,
        /** true 时用 mmap 读权重，省内存但首次加载可能更慢。 */
        val useMmap: Boolean = false,
        /** 动态量化档位，0 = 关。 */
        val dynamicOption: Int = 0,
        /**
         * 是否保留思考段。项目硬约束：必须为 true。
         * 模型自带 jinja 模板只在 `enable_thinking == false` 时插入空的 `<think>` 段来抑制思考，
         * 显式传 true 与「不传」等价，写出来是为了杜绝将来被误改成 false。
         */
        val enableThinking: Boolean = true
    ) {
        /** `Llm::set_config()` 接受的 JSON 文本。 */
        fun toJson(): String {
            val root = JSONObject()
            root.put("backend_type", backend)
            root.put("thread_num", threadNum)
            root.put("max_all_tokens", maxAllTokens)
            root.put("max_new_tokens", maxNewTokens)
            root.put("precision", precision)
            root.put("power", power)
            root.put("memory", memory)
            root.put("reuse_kv", reuseKv)
            root.put("use_mmap", useMmap)
            root.put("dynamic_option", dynamicOption)
            if (tmpPath.isNotEmpty()) {
                root.put("tmp_path", tmpPath)
            }
            root.put(
                "jinja",
                JSONObject().put(
                    "context",
                    JSONObject().put("enable_thinking", enableThinking)
                )
            )
            return root.toString()
        }

        /** 配置里有没有明显写不通的地方；返回 null 表示没问题。 */
        fun validate(): String? = when {
            backend.isBlank() -> "backend 不能为空"
            threadNum < 1 -> "threadNum 必须 >= 1，当前 $threadNum"
            maxAllTokens < 1 -> "maxAllTokens 必须 >= 1，当前 $maxAllTokens"
            maxNewTokens < 1 -> "maxNewTokens 必须 >= 1，当前 $maxNewTokens"
            backend != BACKEND_CPU && tmpPath.isBlank() ->
                "非 CPU 后端（$backend）必须给 tmpPath，引擎要在里面落 mnn_cachefile.bin"
            !enableThinking -> "enableThinking 必须为 true：关闭思考会显著拉低解卦质量"
            else -> null
        }
    }

    /** 模型加载/推理失败。message 来自 native 侧的具体原因。 */
    class MnnException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
}

/**
 * 一个已加载的 MNN 会话。持有 native 句柄，用完必须 [close]。
 *
 * 单线程使用（native 侧另有互斥锁兜底，但不要指望它做并发调度）；
 * 生成是重活，调用方应放到后台线程/协程里。
 */
class MnnSession private constructor(
    private var handle: Long,
    val configPath: String,
    val config: MnnLlm.MnnConfig
) : Closeable {

    companion object {
        /**
         * 加载模型并返回可用会话。
         *
         * @throws MnnLlm.MnnException so 不可用、配置非法、加载失败都会抛，message 带原因。
         */
        fun open(configPath: String, config: MnnLlm.MnnConfig = MnnLlm.MnnConfig()): MnnSession {
            MnnLlm.loadError?.let {
                throw MnnLlm.MnnException("MNN native 库不可用：$it")
            }
            config.validate()?.let {
                throw MnnLlm.MnnException("MNN 配置非法：$it")
            }
            if (configPath.isBlank()) {
                throw MnnLlm.MnnException("configPath 不能为空")
            }

            val handle = MnnLlm.nativeCreate(configPath, config.toJson())
            if (handle == 0L) {
                throw MnnLlm.MnnException("nativeCreate 返回 0（句柄分配失败）")
            }

            val error = MnnLlm.nativeLastError(handle)
            if (error.isNotEmpty()) {
                // 句柄虽然非 0，但模型没起来；先释放再抛，避免泄漏几十 MB 权重。
                MnnLlm.nativeRelease(handle)
                throw MnnLlm.MnnException("MNN 会话加载失败：$error")
            }

            val session = MnnSession(handle, configPath, config)
            Log.i(
                "MnnLlm",
                "会话就绪 ${session.loadMillis}ms backend=${config.backend} " +
                    "thread=${config.threadNum} ctx=${config.maxAllTokens}"
            )
            return session
        }

        /** MNN 版本号；so 不可用时返回空串。 */
        fun version(): String = if (MnnLlm.isAvailable) MnnLlm.nativeVersion() else ""
    }

    /** native 句柄还在（没被 [close] 过）。常驻缓存靠它判断要不要重新加载。 */
    val isOpen: Boolean get() = handle != 0L

    /** 模型加载耗时（毫秒）。 */
    val loadMillis: Long
        get() = if (handle == 0L) 0L else MnnLlm.nativeLoadMillis(handle)

    /** 引擎实际生效的配置，用来确认 backend/线程/上下文有没有被静默忽略。 */
    fun dumpConfig(): String = if (handle == 0L) "" else MnnLlm.nativeDumpConfig(handle)

    /** 最近一次失败原因；空串表示无错。 */
    fun lastError(): String = if (handle == 0L) "已释放" else MnnLlm.nativeLastError(handle)

    /**
     * 跑一次解卦补全。
     *
     * @param maxTokens 见 [MnnLlm.nativeComplete]；默认 -1 = 用配置里的 maxNewTokens。
     * @param resetHistory 默认 true，每次解卦互相独立。
     */
    @Synchronized
    fun complete(
        system: String,
        user: String,
        maxTokens: Int = -1,
        resetHistory: Boolean = true
    ): MnnLlm.MnnResult {
        check(handle != 0L) { "MnnSession 已关闭" }
        val text = MnnLlm.nativeComplete(handle, system, user, maxTokens, resetHistory)
        val stats = MnnLlm.MnnStats.from(MnnLlm.nativeLastStats(handle))
        val error = MnnLlm.nativeLastError(handle)
        if (error.isNotEmpty()) {
            Log.w("MnnLlm", "complete 异常收尾：$error（status=${stats.statusName}）")
        }
        return MnnLlm.MnnResult(text, stats)
    }

    /** 清空 KV 与对话历史，模型不卸载。 */
    @Synchronized
    fun reset() {
        if (handle != 0L) {
            MnnLlm.nativeReset(handle)
        }
    }

    /** 释放 native 会话与权重；重复调用安全。 */
    @Synchronized
    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) {
            MnnLlm.nativeRelease(h)
        }
    }
}
