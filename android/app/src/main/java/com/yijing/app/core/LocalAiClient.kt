package com.yijing.app.core

import android.content.Context
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本地小模型解读：调用内嵌 MNN 3.6.1 跑 Qwen3-1.7B，全程离线、无需外部服务。
 *
 * 引擎从 llama.cpp 换成 MNN 的决定性理由：MNN 自带 OpenCL 后端，
 * 高通 Adreno 与华为 Kirin/Mali 都能吃上 GPU；llama.cpp 在 Android 上只有 CPU 路径。
 * 换引擎后 CPU 那套线程数调优继续沿用（见 [threadCount]），但主线是 GPU 加速。
 */
object LocalAiClient {

    /** 一次本地推理的完整耗时拆解，供性能追踪与 debug 面板使用。 */
    data class GenStats(
        val text: String,
        val scope: String,
        val fromCache: Boolean,
        val modelLoadMs: Long,
        val readyWaitMs: Long,
        val queueWaitMs: Long,
        val completeMs: Long,
        val prefillMs: Long,
        val decodeMs: Long,
        val tokens: Int,
        val tokensPerSecond: Float,
        val postMs: Long,
        val totalMs: Long
    )

    /** 基准套件里的一行结果。[backend] 是本行**实际生效**的后端（可能因回退与请求的不同）。 */
    data class BenchRow(
        val label: String,
        val threads: Int,
        val backend: String,
        val stats: GenStats
    )

    /** 基准套件整体结果；[scope] 是 PerfTrace 的会话 id，用来取回整份报告。 */
    data class BenchReport(val scope: String, val rows: List<BenchRow>)

    /**
     * 定稿系统提示词：E 方案（精简 + few-shot 短样例）。
     *
     * 背景：Qwen3.5-2B 不开思考时输出容易偏长（基准测试平均 370 字），
     * 单纯在 SYSTEM 里说"短一点"没用（模型会忽略长度要求）。
     * few-shot 给一个短答案的样例，让模型"看见"短答案长什么样，
     * 压短效果最好——从 370 字压到约 130 字，质量也不崩。
     *
     * 注意：non-thinking 模式（[ENABLE_THINKING] = false），
     * 所以 SYSTEM 里不需要考虑思考段。
     */
    val SYSTEM =
        "你是解卦的朋友，说话直白、接地气，简短有力，控制在 100 字内。" +
        "先讲卦的性子，再讲动爻提醒什么，最后说变卦和建议。" +
        "只输出解读，别废话。\n\n" +
        "例：问「今年换工作好不好」，抽到乾卦 2 爻动→同人。\n" +
        "答：你现在势头挺足，但别急着跳，第二爻提醒你先稳住本事、把东西学扎实再动。变卦同人，说明真跳了能找到志同道合的团队，但得是你先有料才行。建议再熬俩月，把手头项目做漂亮了再投。"

    /**
     * 上下文长度。2048 足够（提示词 + few-shot 样例 ≈ 300 token，生成 200 token），
     * 比 4096 省一半 KV 缓存，也更快。
     */
    private const val CONTEXT_SIZE = 2048

    /**
     * 单次生成上限。
     *
     * Qwen3.5-2B non-thinking 模式，few-shot 后平均约 100 token（≈130 字）。
     * 给到 200 留足余量，同时作为保险闸防止偶发的超长输出。
     * 对比：1.7B 开思考时给 768（思考+回答共用）。
     */
    private const val MAX_TOKENS_THINKING = 200

    /**
     * 兜底重跑时的上限。non-thinking 模式一般用不到，但保留以防万一。
     */
    private const val MAX_TOKENS_RETRY = 400

    /**
     * 是否保留 Qwen 的思考段。**本版本为 false（non-thinking 模式）。**
     *
     * 为什么关思考：Qwen3.5-2B 的思考模式在小模型尺寸下刹不住车——
     * 模型会进入英文"Thinking Process"条目化分析，永远不会输出 `</think>`，
     * 思考段会一直吃到 max_tokens 上限才停，导致回答为空白。
     * 2B 这个尺寸的思考能力训练得不成熟，不如直接 non-thinking + few-shot 压短，
     * 质量仍然优于 1.7B 不开思考，速度还更快（总 token 数更少）。
     *
     * MNN 端通过 jinja 模板的 `enable_thinking` 控制（见 [MnnLlm.MnnConfig.enableThinking]），
     * false 时模板会插入空思考块 `<think>\n\n</think>\n\n`，模型直接出答案。
     */
    const val ENABLE_THINKING = false

    /**
     * 首选后端。OpenCL 就是本轮迁移的目标：
     * - MNN 的 OpenCLWrapper 显式覆盖了华为专有路径（`libGLES_mali.so`、
     *   `/vendor/lib64/chipsetsdk/libhvgr_v200.so`、`libOpenCL-pixel.so` 等），
     *   高通 Adreno 走标准 `libOpenCL.so`，两类目标机型都能吃上 GPU；
     * - Android 12+ 需要在 manifest 里声明 `libOpenCL.so`（已声明，required=false）。
     *
     * 真机没有可用的 OpenCL 驱动时 [openWithFallback] 会自动退到 CPU，不会白屏。
     */
    /**
     * 首选推理后端。
     *
     * Qwen3.5-2B-MNN 使用 HQQ 4-bit 量化：
     * - OpenCL：正常输出（高通 Adreno GPU 加速）
     * CPU：HQQ 解量化有兼容问题，输出乱码
     *
     * 真机没有可用的 OpenCL 驱动时 [openWithFallback] 会自动退到 CPU，不会白屏。
     */
    private const val PREFERRED_BACKEND = MnnLlm.BACKEND_OPENCL

    /**
     * 是否用 mmap 读权重。
     *
     * 1.23GB 权重不开 mmap 就得整份常驻内存，再叠加 OpenCL 侧的 GPU 副本，容易顶到上限；
     * 官方文档也建议手机上打开。加载失败会自动退到 false（见 [openWithFallback] 的第三档），
     * 所以这里默认开。
     */
    private const val USE_MMAP = true

    /**
     * 常驻会话缓存 —— Android 端慢的主因。
     *
     * 每问一次就重新加载，等于把 1.2GB 权重重新读盘、重建 KV，光这一步就要十几秒。
     * 这里让会话在进程内只建一次，之后直接复用；只有模型文件换了（重新下载/导入）、
     * 线程数变了、或后端变了才重建。[backend] 为 null 表示「自动」，
     * 即按 [PREFERRED_BACKEND] 起、起不来再退 CPU。
     */
    // ⚠️ 必须是 data class：sessionFor 里用 `cachedKey == key` 判命中，
    // 普通 class 的 == 是引用比较，每次 new 出来的 key 都判不等 —— 缓存永不命中，
    // 预加载成果会被丢弃、每次解卦白付一次重建（真机实测 324ms + 1635ms ≈ 1.96s）。
    private data class SessionKey(
        val configPath: String,
        val threads: Int,
        val backend: String?
    ) {
        fun describe(): String = "backend=${backend ?: "auto"} · threads=$threads"
    }

    private var cachedSession: MnnSession? = null
    private var cachedKey: SessionKey? = null

    /** 常驻会话实际生效的后端；null 表示还没加载过。 */
    @Volatile
    private var resolvedBackend: String? = null

    /** 常驻会话实际生效的后端（"opencl" / "cpu"）；尚未加载时返回 null。 */
    val activeBackend: String? get() = resolvedBackend

    /** 建会话与推理串行化：同一时刻只跑一次，避免并发解卦争用同一个会话。 */
    private val mutex = Mutex()

    /** 设备上报的核心数（含小核）。 */
    fun coreCount(): Int = Runtime.getRuntime().availableProcessors()

    /**
     * 线程数按 CPU 核心数取，上限 4。
     *
     * 这个上限来自真机基准（8 核机）：4 线程 decode 11.88 tok/s 最快，6 线程反而只有
     * 6.88 tok/s —— 超过 4 个线程容易跑到小核上、又互相抢缓存，越加越慢。
     *
     * 换到 MNN 后该值继续沿用：MNN 的 `thread_num` 同样只作用于 CPU 算子
     * （OpenCL 后端下引擎会自行 `numThread |= 64 | 512` 打开 buffer mode，
     * 拿到的是「CPU 线程数」这一层含义），而且有了 GPU 分担，线程数的边际收益本就变小。
     */
    fun threadCount(): Int = coreCount().coerceIn(2, 4)

    /** 一行描述当前推理配置，用于日志与 debug 面板。 */
    fun describeRuntime(threads: Int = threadCount()): String {
        val version = runCatching { MnnSession.version() }.getOrDefault("")
        return "MNN ${version.ifBlank { "不可用" }} · ctx=$CONTEXT_SIZE · threads=$threads" +
            "（设备 ${coreCount()} 核）· backend=${resolvedBackend ?: "未加载"}" +
            "（首选 $PREFERRED_BACKEND）· 思考=关（non-thinking + few-shot）· mmap=$USE_MMAP"
    }

    // ------------------------------------------------------------------ 会话

    /** 引擎的磁盘缓存目录。非 CPU 后端要在里面落 `mnn_cachefile.bin`。 */
    private fun engineTmpDir(context: Context): File {
        val dir = File(context.filesDir, "mnn")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    /**
     * 组装引擎运行期配置。
     *
     * `tmpPath` 必须给且必须可写：llm.cpp 里非 CPU 后端会执行
     * `setCache(tmpPath + "/mnn_cachefile.bin")`，而 `tmp_path` 为空时它退化成 `"."`，
     * 在 Android 上不可写，OpenCL 内核缓存就落不下来。
     */
    private fun configFor(
        context: Context,
        backend: String,
        threads: Int,
        useMmap: Boolean
    ): MnnLlm.MnnConfig = MnnLlm.MnnConfig(
        backend = backend,
        threadNum = threads,
        maxAllTokens = CONTEXT_SIZE,
        maxNewTokens = MAX_TOKENS_THINKING,
        precision = "normal",
        // 不能给 "low"：MNN 的 OpenCL 低功耗探测只把 Adreno 认成支持，
        // 华为 Mali 会被判成「不支持低功耗」而退回 CPU，等于白折腾。
        power = "normal",
        memory = "low",
        tmpPath = engineTmpDir(context).absolutePath,
        reuseKv = false,
        useMmap = useMmap,
        enableThinking = ENABLE_THINKING
    )

    private class Opened(val session: MnnSession, val backend: String)

    /**
     * 建会话，必要时按降级阶梯回退。
     *
     * 为什么回退必须放在这一层：`Schedule::getAppropriateType` 在「OpenCL 的 creator 存在、
     * 但设备根本没有 OpenCL 驱动」时不会自动退 CPU，而 MNN 的 `MNNGetExtraRuntimeCreator`
     * 与 `class Runtime` 都没暴露在随 APK 打包的公开头文件里，C++ 侧没法预探测。
     * 好在 [MnnSession.open] 在 native 报错时会抛 [MnnLlm.MnnException]，所以这里能试。
     *
     * 阶梯顺序：首选后端 + mmap → CPU + mmap → CPU + 不用 mmap。
     * 第三档用来兜「mmap 权重路径失败」这种情况，保证任何一台真机都能起来。
     *
     * @param backend null 表示自动（按上面三档）；给了具体值就只试这一档，
     *                供基准套件显式对比 CPU 与 OpenCL。
     */
    private fun openWithFallback(
        context: Context,
        scope: String,
        threads: Int,
        backend: String?
    ): Opened {
        // Qwen3.5-2B-MNN 是多模态模型，纯文本推理前先给 llm_config.json 打补丁
        // （关 is_visual / is_mrope，避免 44444 崩溃）。幂等，打过直接返回。
        ModelManager.ensurePatchedLlmConfig(context)

        val configPath = ModelManager.configPath(context)
        val candidates: List<MnnLlm.MnnConfig> = if (backend != null) {
            listOf(configFor(context, backend, threads, USE_MMAP))
        } else {
            buildList {
                add(configFor(context, PREFERRED_BACKEND, threads, USE_MMAP))
                add(configFor(context, MnnLlm.BACKEND_CPU, threads, USE_MMAP))
                if (USE_MMAP) add(configFor(context, MnnLlm.BACKEND_CPU, threads, false))
            }
        }

        var lastError: String? = null
        candidates.forEachIndexed { index, cfg ->
            val t = SystemClock.elapsedRealtime()
            try {
                val session = MnnSession.open(configPath, cfg)
                if (index > 0) {
                    PerfTrace.mark(scope, "后端回退", SystemClock.elapsedRealtime() - t,
                        "已降到第 ${index + 1} 档：backend=${cfg.backend} useMmap=${cfg.useMmap}")
                }
                return Opened(session, cfg.backend)
            } catch (e: MnnLlm.MnnException) {
                lastError = e.message
                PerfTrace.mark(scope, "加载失败", SystemClock.elapsedRealtime() - t,
                    "backend=${cfg.backend} useMmap=${cfg.useMmap}：${e.message}")
            }
        }
        throw MnnLlm.MnnException("MNN 会话加载失败（已试 ${candidates.size} 种配置）：$lastError")
    }

    /** 已建好的会话 + 本次是否命中常驻缓存。 */
    private class SessionSlot(
        val session: MnnSession,
        val fromCache: Boolean,
        val loadMs: Long,
        val backend: String
    )

    /** 取（必要时新建）会话。模型路径 / 线程数 / 后端 变了会释放旧会话重建。 */
    private suspend fun sessionFor(
        context: Context,
        scope: String,
        threads: Int = threadCount(),
        backend: String? = null
    ): SessionSlot {
        val configPath = ModelManager.configPath(context)
        val key = SessionKey(configPath, threads, backend)
        return mutex.withLock {
            val t0 = SystemClock.elapsedRealtime()
            cachedSession?.let { old ->
                if (cachedKey == key && old.isOpen) {
                    PerfTrace.mark(scope, "会话加载", 0,
                        "命中常驻缓存，未重新读盘（${key.describe()} · 实际 backend=${resolvedBackend}）")
                    return SessionSlot(old, fromCache = true, loadMs = 0,
                        backend = resolvedBackend ?: PREFERRED_BACKEND)
                }
            }

            // 换配置就得重建：先把旧会话关掉，1.2GB 权重不能有两份。
            cachedSession?.let { old ->
                val released = SystemClock.elapsedRealtime()
                runCatching { old.close() }
                cachedSession = null
                cachedKey = null
                resolvedBackend = null
                PerfTrace.mark(scope, "释放旧会话", SystemClock.elapsedRealtime() - released)
            }

            PerfTrace.mark(scope, "定位模型文件", SystemClock.elapsedRealtime() - t0,
                "$configPath · 有效 ${ModelManager.formatSize(ModelManager.downloadedBytes(context))}")

            val tLoad = SystemClock.elapsedRealtime()
            val opened = openWithFallback(context, scope, threads, backend)
            val loadMs = SystemClock.elapsedRealtime() - tLoad

            cachedSession = opened.session
            cachedKey = key
            resolvedBackend = opened.backend
            PerfTrace.mark(scope, "会话加载", loadMs,
                "backend=${opened.backend} · threads=$threads · ctx=$CONTEXT_SIZE · useMmap=$USE_MMAP")
            runCatching {
                PerfTrace.mark(scope, "MNN 生效配置", 0,
                    opened.session.dumpConfig().replace("\n", " | "))
            }
            SessionSlot(opened.session, fromCache = false, loadMs = loadMs, backend = opened.backend)
        }
    }

    /** 把模型提前读进内存（只建会话，不推理）。进入主界面后调用，首次解卦即可秒开。 */
    suspend fun preload(context: Context) {
        if (!ModelManager.isDownloaded(context)) return
        val scope = PerfTrace.beginSession("首页预加载模型")
        val t0 = SystemClock.elapsedRealtime()
        runCatching { sessionFor(context, scope) }
        PerfTrace.mark(scope, "预加载总计", SystemClock.elapsedRealtime() - t0)
        lastPreloadFinishedAt = SystemClock.elapsedRealtime()
    }

    /** 预加载完成时刻（elapsedRealtime），用于判断解卦时模型是否已就绪。 */
    @Volatile
    var lastPreloadFinishedAt: Long = 0L
        private set

    /** 模型被删除、或需要换配置时丢弃常驻会话，避免之后拿着已失效的句柄去推理。 */
    fun releaseCache() {
        val session = cachedSession ?: return
        cachedSession = null
        cachedKey = null
        resolvedBackend = null
        runCatching { session.close() }
    }

    fun promptOf(question: String, original: Hexagram, changed: Hexagram, movingLine: Int, yaoText: String): Pair<String, String> {
        val user = AiClient.promptOf(question, original, changed, movingLine, yaoText).second
        return SYSTEM to user
    }

    /**
     * 使用本地模型生成解读。模型未下载时会抛出带提示的异常。
     * 返回 [GenStats]，里面带 prefill / decode / token 速率等拆解数据。
     */
    suspend fun generate(context: Context, system: String, user: String): GenStats {
        val scope = PerfTrace.currentSession()
        return generate(context, system, user, scope, MAX_TOKENS_THINKING, "解卦",
            threadCount(), null, retryIfBlank = true)
    }

    /**
     * 按指定 token 上限生成，用于 debug 基准测试。
     * 保持同一个 [scope] 以便和页面埋点写在同一条会话里。
     *
     * @param threads 本次推理使用的线程数；和常驻会话不一致时会触发一次重建。
     * @param backend 指定后端（[MnnLlm.BACKEND_CPU] / [MnnLlm.BACKEND_OPENCL]）；
     *   null = 自动（首选 OpenCL，起不来退 CPU）。基准套件靠它对比两种后端。
     * @param retryIfBlank 开思考时若答案被思考段挤成空白，是否自动加大预算重跑一次。
     *   基准套件里要故意跑「思考吃光预算」的那种极端配置，所以它会显式传 false。
     */
    suspend fun generate(
        context: Context,
        system: String,
        user: String,
        scope: String,
        maxTokens: Int,
        label: String,
        threads: Int = threadCount(),
        backend: String? = null,
        retryIfBlank: Boolean = true
    ): GenStats {
        val t0 = SystemClock.elapsedRealtime()
        if (!MnnLlm.isAvailable) {
            throw Exception("MNN native 库加载失败：${MnnLlm.loadError}")
        }
        if (!ModelManager.isDownloaded(context)) {
            val detail = ModelManager.verifyMessage(context)
            throw Exception(
                if (detail.isNullOrBlank()) "本地模型尚未下载，请先到「设置」页下载模型（约 1.2GB）"
                else "本地模型文件不完整：$detail"
            )
        }
        PerfTrace.mark(scope, "$label 提示词", 0,
            "system ${system.length} 字 · user ${user.length} 字 · maxTokens=$maxTokens · " +
                "threads=$threads · backend=${backend ?: "auto($PREFERRED_BACKEND→cpu)"} · 思考=关")

        // 会话是否已经就绪？这是首次解卦最容易被忽略的一段等待。
        val tReady = SystemClock.elapsedRealtime()
        val slot = sessionFor(context, scope, threads, backend)
        val readyWaitMs = SystemClock.elapsedRealtime() - tReady
        val preloaded = lastPreloadFinishedAt
        PerfTrace.mark(scope, "等待会话就绪", readyWaitMs,
            if (preloaded > 0) "首页预加载已于 ${(SystemClock.elapsedRealtime() - preloaded) / 1000}s 前完成"
            else "预加载未完成（未进首页 / 还没跑完），这段等待被算进解卦")
        val tAfterModel = SystemClock.elapsedRealtime()

        return mutex.withLock {
            val queueWaitMs = SystemClock.elapsedRealtime() - tAfterModel
            PerfTrace.mark(scope, "等待推理锁", queueWaitMs)

            val tComplete = SystemClock.elapsedRealtime()
            val result = slot.session.complete(
                system = system,
                user = user,
                maxTokens = maxTokens,
                resetHistory = true
            )
            val completeMs = SystemClock.elapsedRealtime() - tComplete

            val stats = result.stats
            val prefillMs = stats.prefillMs
            val decodeMs = stats.decodeMs
            val tokens = stats.genSeqLen
            val tps = stats.tokensPerSecond
            val plainMs = (completeMs - prefillMs - decodeMs).coerceAtLeast(0)

            PerfTrace.mark(scope, "$label prefill 预填充", prefillMs,
                "提示词 ${stats.promptLen} token · backend=${slot.backend}，GPU 卸载收益主要在这")
            PerfTrace.mark(scope, "$label decode 解码生成", decodeMs,
                "$tokens token · ${"%.2f".format(tps)} tok/s")
            PerfTrace.mark(scope, "$label complete() 总耗时", completeMs,
                "prefill+decode=${prefillMs + decodeMs}ms · 其余 ${plainMs}ms · " +
                    "采样 ${stats.sampleUs / 1000}ms · 首字 ${stats.ttfaUs / 1000}ms")
            if (!stats.isFinished) {
                PerfTrace.mark(scope, "注意", 0,
                    "引擎状态 ${stats.statusName}：${slot.session.lastError()}")
            }
            if (tokens >= maxTokens) {
                PerfTrace.mark(scope, "注意", 0,
                    "生成 $tokens token 已打满上限 $maxTokens，模型没吐结束符，等于白等满额上限")
            }

            // 把原文开头写进日志：用来确认思考段到底有没有出现、有没有被截断。
            val rawHead = result.text.take(160).replace("\n", "\\n")
            PerfTrace.mark(scope, "$label 原始输出开头", 0, "「$rawHead」")
            // 只看标签是假阳性：模型也会吐出空壳 <think></think>。
            // 所以这里量的是「思考块里到底有没有内容」（含未闭合的块，被截断的那种也算）。
            val thinkChars = Regex("(?is)<\\s*think\\s*>(.*?)(</\\s*think\\s*>|$)")
                .findAll(result.text)
                .sumOf { it.groupValues[1].trim().length }
            if (thinkChars > 0) {
                PerfTrace.mark(scope, "$label 思考段", 0, "思考 $thinkChars 字（保留思考是硬约束）")
            }

            val tPost = SystemClock.elapsedRealtime()
            var text = stripThinking(result.text)
            var postMs = SystemClock.elapsedRealtime() - tPost
            PerfTrace.mark(scope, "$label 后处理去思考标签", postMs,
                "原文 ${result.text.length} 字 → ${text.length} 字")

            // 兜底：思考段把 token 预算吃光时，模型其实还没开始写答案就被截断了，
            // 去掉思考标签后就是空白。MNN 上不能靠 /no_think 关思考（模板里没有这个分支），
            // 所以改成「把预算加大再跑一次」——宁可多等一轮，也不让用户拿到空白解读。
            if (text.isBlank() && retryIfBlank && maxTokens < MAX_TOKENS_RETRY) {
                PerfTrace.mark(scope, "$label 空结果兜底", 0,
                    "思考段吃光 $maxTokens token、答案被截断，预算提到 $MAX_TOKENS_RETRY 重跑一次")
                val tRetry = SystemClock.elapsedRealtime()
                val retry = slot.session.complete(
                    system = system,
                    user = user,
                    maxTokens = MAX_TOKENS_RETRY,
                    resetHistory = true
                )
                val tStrip = SystemClock.elapsedRealtime()
                text = stripThinking(retry.text)
                postMs += SystemClock.elapsedRealtime() - tRetry
                PerfTrace.mark(scope, "$label 兜底重跑", SystemClock.elapsedRealtime() - tRetry,
                    "${retry.stats.genSeqLen} token · ${"%.2f".format(retry.stats.tokensPerSecond)} tok/s · " +
                        "状态 ${retry.stats.statusName} · 去标签后 ${text.length} 字" +
                        "（其中去标签 ${SystemClock.elapsedRealtime() - tStrip}ms）")
            }

            val totalMs = SystemClock.elapsedRealtime() - t0
            PerfTrace.mark(scope, "$label 模型侧总计", totalMs)

            GenStats(
                text = text,
                scope = scope,
                fromCache = slot.fromCache,
                modelLoadMs = slot.loadMs,
                readyWaitMs = readyWaitMs,
                queueWaitMs = queueWaitMs,
                completeMs = completeMs,
                prefillMs = prefillMs,
                decodeMs = decodeMs,
                tokens = tokens,
                tokensPerSecond = tps,
                postMs = postMs,
                totalMs = totalMs
            )
        }
    }

    /**
     * debug 基准套件：同一段固定提示词，换后端各跑一轮，用实测数据回答一个问题：
     *
     * **OpenCL 到底比 CPU 快多少？** 这正是换 MNN 的动机所在，所以 A/B 两组就是它。
     *
     * 原来那三组（思考开关、全核、4 线程）已经不再需要：
     * 关思考是硬约束禁止的；线程数调优在 llama.cpp 时代就做完了，结论（上限 4）继续沿用。
     * 注意换后端必然要重建会话（模型冷加载一次），所以耗时会明显变长。
     */
    suspend fun benchSuite(context: Context, maxTokens: Int = 96): BenchReport {
        val scope = PerfTrace.beginSession("基准套件 maxTokens=$maxTokens")
        val probe = "请用一句话说明「乾卦」主要讲什么。"
        PerfTrace.mark(scope, "设备与配置", 0,
            "${PerfTrace.deviceLine()} | ${describeRuntime()}")

        val plan = listOf(
            Pair("A OpenCL（首选）", MnnLlm.BACKEND_OPENCL),
            Pair("B CPU（对照）", MnnLlm.BACKEND_CPU)
        )

        val rows = ArrayList<BenchRow>()
        for ((label, backend) in plan) {
            if (!ModelManager.isDownloaded(context)) break
            // 换后端必须重建会话：先丢缓存，让 sessionFor 冷加载一次。
            releaseCache()
            val t = SystemClock.elapsedRealtime()
            val stats = runCatching {
                // 这里要故意跑「思考吃光预算」的极端配置，兜底重跑会把对照数据洗掉，所以关掉。
                generate(context, SYSTEM, probe, scope, maxTokens, label,
                    threadCount(), backend, retryIfBlank = false)
            }.getOrNull()
            if (stats == null) {
                PerfTrace.mark(scope, "$label 失败", SystemClock.elapsedRealtime() - t, "见上一条异常")
                continue
            }
            val real = activeBackend ?: backend
            PerfTrace.mark(scope, "小结 $label", SystemClock.elapsedRealtime() - t,
                "threads=${threadCount()} · 实际 backend=$real · prefill ${stats.prefillMs}ms · " +
                    "decode ${stats.decodeMs}ms · ${stats.tokens} token · " +
                    "${"%.2f".format(stats.tokensPerSecond)} tok/s")
            rows.add(BenchRow(label, threadCount(), real, stats))
        }
        // 收尾：把常驻会话还原成默认配置（首选后端 + 自动回退），免得基准跑完留在 CPU 上。
        // 顺带把冷加载耗时也量出来（这段不计入任何一组结果）。
        releaseCache()
        if (ModelManager.isDownloaded(context)) {
            val t = SystemClock.elapsedRealtime()
            runCatching { sessionFor(context, scope) }
            PerfTrace.mark(scope, "还原默认配置（冷加载）", SystemClock.elapsedRealtime() - t,
                "实际 backend=${activeBackend ?: "?"} · threads=${threadCount()}")
        }
        return BenchReport(scope, rows)
    }

    /**
     * 系统提示词里那些「要求」的指纹词。
     *
     * 模型偶尔会把自己的指令当正文复述出来 —— 真机截图里答案末尾就整句贴了
     * 「（字数控制在150字以内，像聊天一样自然）」。见 [dropInstructionEcho]。
     *
     * 只收「正常解卦绝不会出现」的词：像「该怎么做」这种正文里真会出现的说法一律不收，
     * 免得把好端端的解读尾巴切掉。
     */
    private val INSTRUCTION_ECHO_SEEDS = listOf(
        "字数控制", "字以内", "百来字", "像聊天一样", "聊天一样自然",
        "文绉绉", "积累经验", "自相矛盾", "用「你」称呼", "只输出解读", "不要复述"
    )

    /**
     * 剥掉贴在末尾的「系统提示词回音」。
     *
     * 只从**末尾**逐句剥，剥到第一句不像回音的句子就停：复述要求一定出现在结尾，
     * 从尾巴上动手不会误伤正文。
     */
    private fun dropInstructionEcho(input: String): String {
        // 按句切开，并且把句间的空白/换行一起留在本句尾部 —— 直接丢掉会毁掉正文的段落。
        val sentence = Regex("\\s*[^。！？!?\\n]+[。！？!?]\\s*|\\s*[^。！？!?\\n]+")
        val parts = sentence.findAll(input.trim()).map { it.value }.toMutableList()
        while (parts.size > 1) {
            val last = parts.last()
            if (last.length <= ECHO_MAX_CHARS && INSTRUCTION_ECHO_SEEDS.any { last.contains(it) }) {
                parts.removeAt(parts.size - 1)
            } else {
                break
            }
        }
        val joined = parts.joinToString("")
        // 整段输出就是一句回音（模型一个字正文都没写）：返回空串，让 [generate] 的兜底重跑接手。
        if (joined.length <= ECHO_MAX_CHARS && INSTRUCTION_ECHO_SEEDS.any { joined.contains(it) }) return ""
        return joined
    }

    /**
     * 去掉 Qwen3 的思考过程标签及内容，只保留最终回答。
     *
     * 真机（小米旗舰）反馈过「思考过程漏到答案里」，所以这里的清洗比早期版本彻底得多。
     * 另外 MNN 官方只在**提示词缓存**里做同类清理（`prompt_cache_utils.hpp` 的
     * `stripThinkBlocks`，只被 `llm.cpp` 的 `updateCachedPromptText` / `syncPromptCache` 调用），
     * `response()` 吐出来的生成文本它一概不管 —— 生成侧的兜底只能我们自己扛。
     *
     * 处理顺序（iOS 端 `LocalAiClient.swift` 的同名函数必须逐条一致）：
     * 1. 循环删掉成对的思考块（`<think>` / `<thinking>` 混写也认）；
     * 2. 删掉落单的 `</think>` 闭合标签（模板已含 `<think>` 时，模型只会补一个闭合标签）；
     * 3. 还有没闭合的开标签，就从那里截断（被 token 上限砍断的思考段）；
     * 4. 清掉残留的对话模板标记，以及纯文本界面里只会显示成星号的 Markdown 加粗；
     * 5. 剥掉尾巴上复述系统要求的回音（[dropInstructionEcho]）。
     */
    fun stripThinking(raw: String): String {
        var s = raw

        // 1) 成对思考块：循环删。早期实现只删第一个，模型吐两段就漏一段。
        val pair = Regex("(?is)<\\s*think(?:ing)?\\s*>.*?</\\s*think(?:ing)?\\s*>")
        while (true) {
            val next = pair.replace(s, "")
            if (next == s) break
            s = next
        }

        // 2) 落单的闭合标签：删标签本身，不要当成截断点（否则会把后面的正文一起丢掉）。
        s = Regex("(?is)</\\s*think(?:ing)?\\s*>").replace(s, "")

        // 3) 未闭合的开标签：思考段被截断，从这里全砍。
        Regex("(?is)<\\s*think(?:ing)?\\s*>").find(s)?.let { s = s.substring(0, it.range.first) }

        // 4) 对话模板标记（response 包裹、Qwen 的 <|im_start|> 等）+ Markdown 加粗星号。
        s = Regex("(?is)<\\s*\\|?\\s*/?\\s*(response|assistant|im_start|im_end|endoftext|im_sep)\\s*\\|?\\s*>")
            .replace(s, "")
        // 上面的标签清掉后，模板里的 `<|im_start|>assistant\n` 会剩一个裸露的角色词在开头。
        s = Regex("(?is)^\\s*(?:assistant|user|system)\\s*\\n").replace(s, "")
        s = s.replace("**", "")

        // 5) 末尾的系统提示词回音。
        return dropInstructionEcho(s).trim()
    }

    /** 「回音」单句的长度上限：比这更长就不像复述要求了，宁可不剥。 */
    private const val ECHO_MAX_CHARS = 60
}
