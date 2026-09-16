package com.yijing.app.core

import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本地小模型解读：调用内嵌 llama.cpp 跑 Qwen3-1.7B GGUF，全程离线、无需外部服务。
 */
object LocalAiClient {

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
     * 单次生成上限。系统提示词要求「150 字以内」，512 token 有足够余量；
     * 原来的 1024 是模型万一不吐结束符时的最坏耗时，砍掉一半即省一半最坏等待。
     */
    private const val MAX_TOKENS = 512

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

    /** 推理与加载串行化：同一时刻只跑一次，避免并发解卦争用同一个模型。 */
    private val mutex = Mutex()

    /**
     * 线程数按 CPU 核心数取，上限 6：再多会跑到小核上，反而拖慢单次响应。
     */
    private fun threadCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    /** 取（必要时加载）模型。文件路径变了会释放旧模型重新加载。 */
    private suspend fun modelFor(context: Context): LlamaModel {
        val path = ModelManager.modelFile(context).absolutePath
        return mutex.withLock {
            cachedModel?.let { if (cachedPath == path && it.isLoaded) return it }
            cachedModel?.let { runCatching { Llama.releaseModel(it) } }
            cachedModel = null
            cachedPath = null
            val model = Llama.loadModel(
                modelPath = path,
                // gpuLayers 用默认 0：AAR 里只有 CPU 后端（libggml-cpu.so），没有 Metal 那样的 GPU 卸载可用。
                config = LlamaConfig(contextSize = CONTEXT_SIZE, threads = threadCount())
            )
            cachedModel = model
            cachedPath = path
            model
        }
    }

    /** 把模型提前读进内存（只加载，不推理）。进入主界面后调用，首次解卦即可秒开。 */
    suspend fun preload(context: Context) {
        if (!ModelManager.isDownloaded(context)) return
        modelFor(context)
    }

    /** 模型被删除时丢弃缓存，避免之后拿着已失效的句柄去推理。 */
    fun releaseCache() {
        val model = cachedModel ?: return
        cachedModel = null
        cachedPath = null
        runCatching { Llama.releaseModel(model) }
    }

    fun promptOf(question: String, original: Hexagram, changed: Hexagram, movingLine: Int, yaoText: String): Pair<String, String> {
        val user = AiClient.promptOf(question, original, changed, movingLine, yaoText).second
        return SYSTEM to user
    }

    /**
     * 使用本地模型生成解读。模型未下载时会抛出带提示的异常。
     */
    suspend fun generate(context: Context, system: String, user: String): String {
        if (!ModelManager.isDownloaded(context)) {
            throw Exception("本地模型尚未下载，请先到「设置」页下载模型（约 1.2GB）")
        }
        val model = modelFor(context)
        return mutex.withLock {
            val result = Llama.complete(
                model = model,
                prompt = user,
                systemPrompt = system,
                maxTokens = MAX_TOKENS
            )
            stripThinking(result.text)
        }
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
