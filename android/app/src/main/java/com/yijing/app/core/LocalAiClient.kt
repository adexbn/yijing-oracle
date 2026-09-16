package com.yijing.app.core

import android.content.Context
import android.os.SystemClock
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本地小模型解读：调用内嵌 llama.cpp 跑 Qwen3-1.7B GGUF，全程离线、无需外部服务。
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

    /** 基准套件里的一行结果。 */
    data class BenchRow(
        val label: String,
        val threads: Int,
        val noThink: Boolean,
        val stats: GenStats
    )

    /** 基准套件整体结果；[scope] 是 PerfTrace 的会话 id，用来取回整份报告。 */
    data class BenchReport(val scope: String, val rows: List<BenchRow>)

    /** 定稿系统提示词：直白、有对象感、建议具体。 */
    val SYSTEM = "你是一个会解卦的朋友，说话直白、接地气，像跟人当面聊天，千万不要文绉绉、不要用文言字眼。" +
        "按这个顺序用大白话讲：1. 先说他抽到的是什么卦，这个卦本身代表什么状态、什么性子（用生活里的话讲）。" +
        "2. 再说动的那一爻在提醒什么（把爻辞翻译成大白话，讲它对人有什么实际意思）。" +
        "3. 再说变成的那个卦，点明事情会往哪个方向走。" +
        "4. 最后紧扣他问的具体问题，给几句实实在在的建议，包括该怎么做、要注意和避免什么。" +
        "建议要具体到眼下能做的事，别给「积累经验」这类泛泛的话，而且建议的方向要和前面的结论一致，不要自相矛盾。" +
        "全程用「你」称呼问卦的人，控制在150字以内，像聊天一样自然。"

    /**
     * 上下文 2048 足够（提示词很短），比原来的 4096 省一半 KV 缓存，也更快。
     */
    private const val CONTEXT_SIZE = 2048

    /**
     * 不思考时的单次生成上限。系统提示词要求「150 字以内」，512 token 有足够余量；
     * 原来的 1024 是模型万一不吐结束符时的最坏耗时，砍掉一半即省一半最坏等待。
     */
    private const val MAX_TOKENS = 512

    /**
     * 开思考时的单次生成上限。思考段和正式回答共用同一份 token 预算：
     * 真机基准里 maxTokens=96 时，思考段把预算吃光、去标签后一个字不剩，
     * 所以开思考必须给足。实测思考段约 250 token、回答约 100 token，留到 768 仍有余量。
     */
    private const val MAX_TOKENS_THINKING = 768

    /**
     * 是否关掉 Qwen3 的思考段。当前取值是 **false（保留思考）**。
     *
     * Qwen3 官方软开关：在用户提问末尾追加 `/no_think`，模型就不再吐思考段。
     * 真机数据（8 核机 · Qwen3-1.7B Q4_K_M）：一次解卦 decode 生成 329 token / 49.90s，
     * 但去掉思考标签后答案只有 100 字（约 70 token）—— 约 4/5 的解码时间烧在思考上。
     * 曾据此默认关思考（decode 49.90s → 12.21s、端到端 62s → 25.2s）。
     *
     * 但 1.7B 这种小模型的思考段不是浪费：少了它，答案明显变短变平、缺了那层权衡，
     * 质量下降比省下的几十秒更不划算。所以回滚成默认思考，多等一会儿换一份更实的解读。
     * 只影响本地模型；云端解读走外部大模型，不受这里影响。
     *
     * 注意：该库的 JNI 只把 system+user 两轮交给模型自带的聊天模板，
     * 不传 enable_thinking 这类模板参数，所以只能靠提示词软开关。
     */
    const val DISABLE_THINKING = false

    /** 追加到 user 提示词末尾的软开关标记（前面留空格，避免和问句黏在一起）。 */
    private const val NO_THINK_MARK = " /no_think"

    /**
     * 模型常驻缓存 —— Android 端慢的主因。
     *
     * 原来每次解卦都要 Llama.loadModel → Llama.complete → Llama.releaseModel，
     * 等于每问一次就把 1.2GB 的 GGUF 重新读盘、重新建 KV 缓存，光这一步就要十几秒。
     * 这里仿照 iOS 端的 LlamaRuntime：模型在进程内只加载一次，之后直接复用，
     * 只有模型文件换了（重新下载/导入）才重新加载。
     */
    private var cachedModel: LlamaModel? = null
    private var cachedPath: String? = null

    /** 常驻模型当初用多少线程建的 context；线程数变了必须重新加载才生效。 */
    private var cachedThreads: Int = 0

    /** 推理与加载串行化：同一时刻只跑一次，避免并发解卦争用同一个模型。 */
    private val mutex = Mutex()

    /** 设备上报的核心数（含小核）。 */
    fun coreCount(): Int = Runtime.getRuntime().availableProcessors()

    /**
     * 线程数按 CPU 核心数取，上限 4。
     *
     * 真机基准（8 核机 · 不思考）：4 线程 decode 11.88 tok/s 最快，6 线程反而只有 6.88 tok/s
     * —— 超过 4 个线程容易跑到小核上、又互相抢缓存，越加越慢。所以上限钉在 4。
     *
     * 已确认：该值会被 JNI 原样写进 llama_context_params 的 n_threads 与 n_threads_batch，
     * 库不做改写也不做钳制（JNI 不使用 llama.cpp 的 common 库，所以不会触发「-1 → 自动探测核数」）。
     */
    fun threadCount(): Int = coreCount().coerceIn(2, 4)

    /** 一行描述当前推理配置，用于日志与 debug 面板。 */
    fun describeRuntime(threads: Int = threadCount()): String =
        "ctx=$CONTEXT_SIZE · threads=$threads（设备 ${coreCount()} 核）· maxTokens=$MAX_TOKENS · " +
            "gpuLayers=0（AAR 无 GPU 后端）· noThink=$DISABLE_THINKING"

    /** 取（必要时加载）模型。文件路径或线程数变了会释放旧模型重新加载。 */
    private suspend fun modelFor(
        context: Context,
        scope: String,
        threads: Int = threadCount()
    ): ModelSlot {
        val path = ModelManager.modelFile(context).absolutePath
        return mutex.withLock {
            val t0 = SystemClock.elapsedRealtime()
            cachedModel?.let {
                if (cachedPath == path && cachedThreads == threads && it.isLoaded) {
                    PerfTrace.mark(scope, "模型加载", 0, "命中常驻缓存，未重新读盘（threads=$threads）")
                    return ModelSlot(it, fromCache = true, loadMs = 0)
                }
            }
            cachedModel?.let {
                val released = SystemClock.elapsedRealtime()
                runCatching { Llama.releaseModel(it) }
                PerfTrace.mark(scope, "释放旧模型", SystemClock.elapsedRealtime() - released)
            }
            cachedModel = null
            cachedPath = null
            val file = ModelManager.modelFile(context)
            PerfTrace.mark(scope, "定位模型文件", SystemClock.elapsedRealtime() - t0,
                "${file.absolutePath} · ${file.length() / 1048576}MB")
            val loadStart = SystemClock.elapsedRealtime()
            val model = Llama.loadModel(
                modelPath = path,
                // gpuLayers 用默认 0：AAR 里只有 CPU 后端（libggml-cpu.so），没有 Metal 那样的 GPU 卸载可用。
                config = LlamaConfig(contextSize = CONTEXT_SIZE, threads = threads)
            )
            val loadMs = SystemClock.elapsedRealtime() - loadStart
            cachedModel = model
            cachedPath = path
            cachedThreads = threads
            PerfTrace.mark(scope, "模型加载", loadMs, "冷加载 mmap+建 KV 缓存 · threads=$threads")
            runCatching {
                val c = model.config
                PerfTrace.mark(scope, "Llama 配置", 0,
                    "ctx=${c.contextSize} threads=${c.threads} gpuLayers=${c.gpuLayers} " +
                        "· 设备核数 ${Runtime.getRuntime().availableProcessors()}")
            }
            ModelSlot(model, fromCache = false, loadMs = loadMs)
        }
    }

    private class ModelSlot(val model: LlamaModel, val fromCache: Boolean, val loadMs: Long)

    /** 把模型提前读进内存（只加载，不推理）。进入主界面后调用，首次解卦即可秒开。 */
    suspend fun preload(context: Context) {
        if (!ModelManager.isDownloaded(context)) return
        val scope = PerfTrace.beginSession("首页预加载模型")
        val t0 = SystemClock.elapsedRealtime()
        runCatching { modelFor(context, scope) }
        PerfTrace.mark(scope, "预加载总计", SystemClock.elapsedRealtime() - t0)
        lastPreloadFinishedAt = SystemClock.elapsedRealtime()
    }

    /** 预加载完成时刻（elapsedRealtime），用于判断解卦时模型是否已就绪。 */
    @Volatile
    var lastPreloadFinishedAt: Long = 0L
        private set

    /** 模型被删除时丢弃缓存，避免之后拿着已失效的句柄去推理。 */
    fun releaseCache() {
        val model = cachedModel ?: return
        cachedModel = null
        cachedPath = null
        cachedThreads = 0
        runCatching { Llama.releaseModel(model) }
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
        // 开思考时多给一截预算：思考段和回答共用同一份 token，给少了会只剩思考、没有答案。
        val budget = if (DISABLE_THINKING) MAX_TOKENS else MAX_TOKENS_THINKING
        return generate(context, system, user, scope, budget, "解卦",
            threadCount(), DISABLE_THINKING)
    }

    /**
     * 按指定 token 上限生成，用于 debug 基准测试。
     * 保持同一个 [scope] 以便和页面埋点写在同一条会话里。
     *
     * @param threads 本次推理使用的线程数；和常驻模型不一致时会触发一次冷加载。
     * @param noThink 是否在提问末尾追加 /no_think（关掉 Qwen3 的思考段）。
     * @param retryIfBlank 开思考时若答案被思考段挤成空白，是否自动改用 /no_think 重跑一次。
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
        noThink: Boolean = DISABLE_THINKING,
        retryIfBlank: Boolean = true
    ): GenStats {
        val t0 = SystemClock.elapsedRealtime()
        if (!ModelManager.isDownloaded(context)) {
            throw Exception("本地模型尚未下载，请先到「设置」页下载模型（约 1.2GB）")
        }
        // /no_think 是软开关，只有真的出现在提示词里才起作用，所以在最靠模型的一步拼进去。
        val effectiveUser =
            if (noThink && !user.contains(NO_THINK_MARK.trim())) user + NO_THINK_MARK else user
        PerfTrace.mark(scope, "$label 提示词", 0,
            "system ${system.length} 字 · user ${user.length} 字 · maxTokens=$maxTokens · threads=$threads · " +
                (if (noThink) "已加 /no_think（不思考）" else "未加 /no_think（会思考）"))

        // 模型是否已经就绪？这是首次解卦最容易被忽略的一段等待。
        val tReady = SystemClock.elapsedRealtime()
        val slot = modelFor(context, scope, threads)
        val readyWaitMs = SystemClock.elapsedRealtime() - tReady
        val preloaded = lastPreloadFinishedAt
        PerfTrace.mark(scope, "等待模型就绪", readyWaitMs,
            if (preloaded > 0) "首页预加载已于 ${(SystemClock.elapsedRealtime() - preloaded) / 1000}s 前完成"
            else "预加载未完成（未进首页 / 还没跑完），这段等待被算进解卦")
        val tAfterModel = SystemClock.elapsedRealtime()

        return mutex.withLock {
            val queueWaitMs = SystemClock.elapsedRealtime() - tAfterModel
            PerfTrace.mark(scope, "等待推理锁", queueWaitMs)

            val tComplete = SystemClock.elapsedRealtime()
            val result = Llama.complete(
                model = slot.model,
                prompt = effectiveUser,
                systemPrompt = system,
                maxTokens = maxTokens
            )
            val completeMs = SystemClock.elapsedRealtime() - tComplete

            val prefillMs = result.promptEvalTimeMs
            val decodeMs = result.generateTimeMs
            val tokens = result.tokensGenerated
            val tps = result.tokensPerSecond
            val plainMs = (completeMs - prefillMs - decodeMs).coerceAtLeast(0)

            PerfTrace.mark(scope, "$label prefill 预填充", prefillMs, "吃提示词的阶段，GPU 卸载收益主要在这")
            PerfTrace.mark(scope, "$label decode 解码生成", decodeMs,
                "$tokens token · ${"%.2f".format(tps)} tok/s")
            PerfTrace.mark(scope, "$label complete() 总耗时", completeMs,
                "prefill+decode=${prefillMs + decodeMs}ms · 其余 ${plainMs}ms")
            if (tokens >= maxTokens) {
                PerfTrace.mark(scope, "注意", 0,
                    "生成 $tokens token 已打满上限 $maxTokens，模型没吐结束符，等于白等满额上限")
            }

            // 把原文开头写进日志：用来确认 /no_think 到底有没有生效（有没有 think 段）。
            val rawHead = result.text.take(160).replace("\n", "\\n")
            PerfTrace.mark(scope, "$label 原始输出开头", 0, "「$rawHead」")
            // 只看标签是假阳性：/no_think 生效时模型也会吐出空壳 <think></think>。
            // 所以这里量的是「思考块里到底有没有内容」（含未闭合的块，被截断的那种也算）。
            val thinkChars = Regex("(?is)<\\s*think\\s*>(.*?)(</\\s*think\\s*>|$)")
                .findAll(result.text)
                .sumOf { it.groupValues[1].trim().length }
            if (thinkChars > 0) {
                PerfTrace.mark(scope, "$label 思考段", 0,
                    "思考 $thinkChars 字 —— " +
                        (if (noThink) "/no_think 没起作用，思考 token 白烧解码时间" else "默认：保留思考")
                )
            }

            val tPost = SystemClock.elapsedRealtime()
            var text = stripThinking(result.text)
            var postMs = SystemClock.elapsedRealtime() - tPost
            PerfTrace.mark(scope, "$label 后处理去思考标签", postMs,
                "原文 ${result.text.length} 字 → ${text.length} 字")

            // 兜底：开思考时，只要思考段把 token 预算吃光，模型其实还没开始写答案就被截断了，
            // 去掉思考标签后就是空白。宁可多等一轮，也不能让用户拿到空白解读。
            if (text.isBlank() && !noThink && retryIfBlank) {
                PerfTrace.mark(scope, "$label 空结果兜底", 0,
                    "思考段吃光 $maxTokens token、答案被截断，改用 /no_think 重跑一次")
                val tRetry = SystemClock.elapsedRealtime()
                val retryUser =
                    if (user.contains(NO_THINK_MARK.trim())) user else user + NO_THINK_MARK
                val retry = Llama.complete(
                    model = slot.model,
                    prompt = retryUser,
                    systemPrompt = system,
                    maxTokens = maxTokens
                )
                val tStrip = SystemClock.elapsedRealtime()
                text = stripThinking(retry.text)
                val retryMs = SystemClock.elapsedRealtime() - tRetry
                postMs += retryMs
                PerfTrace.mark(scope, "$label 兜底重跑", retryMs,
                    "${retry.tokensGenerated} token · ${"%.2f".format(retry.tokensPerSecond)} tok/s · " +
                        "去标签后 ${text.length} 字（其中去标签 ${SystemClock.elapsedRealtime() - tStrip}ms）")
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
     * debug 基准套件：同一段固定提示词，换不同开关各跑一轮，用实测数据回答两个问题。
     *
     * 1. 思考到底吃掉多少解码时间 —— 对比 A（思考开）与 B（不思考）的 token 数与 tok/s。
     * 2. 线程数还有没有空间 —— 对比 B（默认线程数，上限 4）与 C（全核）的 decode 耗时。
     *    注意换线程数必然要重建 context（模型冷加载一次），所以耗时会明显变长。
     */
    suspend fun benchSuite(context: Context, maxTokens: Int = 96): BenchReport {
        val scope = PerfTrace.beginSession("基准套件 maxTokens=$maxTokens")
        val probe = "请用一句话说明「乾卦」主要讲什么。"
        PerfTrace.mark(scope, "设备与配置", 0,
            "${PerfTrace.deviceLine()} | ${describeRuntime()}")

        val plan = listOf(
            Triple("A 对照·思考开", threadCount(), false),
            Triple("B 不思考（默认）", threadCount(), true),
            Triple("C 不思考·全核", coreCount(), true),
            Triple("D 不思考·4 线程", 4, true)
        ).distinctBy { it.second to it.third }

        val rows = ArrayList<BenchRow>()
        for ((label, threads, noThink) in plan) {
            if (!ModelManager.isDownloaded(context)) break
            // 换线程数必须重建 context：先丢缓存，让 modelFor 冷加载一次。
            releaseCache()
            val t = SystemClock.elapsedRealtime()
            val stats = runCatching {
                // 这里要故意跑「思考吃光预算」的极端配置，兜底重跑会把对照数据洗掉，所以关掉。
                generate(context, SYSTEM, probe, scope, maxTokens, label, threads, noThink,
                    retryIfBlank = false)
            }.getOrNull()
            if (stats == null) {
                PerfTrace.mark(scope, "$label 失败", SystemClock.elapsedRealtime() - t, "见上一条异常")
                continue
            }
            PerfTrace.mark(scope, "小结 $label", SystemClock.elapsedRealtime() - t,
                "threads=$threads · noThink=$noThink · prefill ${stats.prefillMs}ms · " +
                    "decode ${stats.decodeMs}ms · ${stats.tokens} token · " +
                    "${"%.2f".format(stats.tokensPerSecond)} tok/s")
            rows.add(BenchRow(label, threads, noThink, stats))
        }
        // 收尾：把常驻模型还原成默认配置，免得基准跑完留在「4 线程」这种临时状态上。
        // 顺带把冷加载耗时也量出来（这段不计入任何一组结果）。
        releaseCache()
        if (ModelManager.isDownloaded(context)) {
            val t = SystemClock.elapsedRealtime()
            runCatching { modelFor(context, scope, threadCount()) }
            PerfTrace.mark(scope, "还原默认配置（冷加载）", SystemClock.elapsedRealtime() - t,
                "threads=${threadCount()} · noThink=$DISABLE_THINKING")
        }
        return BenchReport(scope, rows)
    }

    /** 去掉 Qwen3 的思考过程标签及内容，只保留最终回答。 */
    fun stripThinking(raw: String): String {
        var s = raw
        // 移除成对的 <thinking> ... </thinking>
        s = Regex("(?is)<\\s*think\\s*>.*?</\\s*think\\s*>").replace(s, "")
        // 思考块未闭合时，从开始标签处截断
        val open = Regex("(?is)<\\s*think\\s*>").find(s)
        if (open != null) s = s.substring(0, open.range.first)
        // 去掉回答标签
        s = Regex("(?is)<\\s*/?\\s*response\\s*>").replace(s, "")
        return s.trim()
    }
}
