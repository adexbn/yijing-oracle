package com.yijing.app.core

import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig

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

    fun promptOf(question: String, original: Hexagram, changed: Hexagram, movingLine: Int, yaoText: String): Pair<String, String> {
        val user = AiClient.promptOf(question, original, changed, movingLine, yaoText).second
        return SYSTEM to user
    }

    /**
     * 使用本地模型生成解读。模型未下载时会抛出带提示的异常。
     */
    suspend fun generate(context: Context, system: String, user: String): String {
        val file = ModelManager.modelFile(context)
        if (!ModelManager.isDownloaded(context)) {
            throw Exception("本地模型尚未下载，请先到「设置」页下载模型（约 1.2GB）")
        }
        val model = Llama.loadModel(
            modelPath = file.absolutePath,
            config = LlamaConfig(contextSize = 4096, threads = 4)
        )
        return try {
            val result = Llama.complete(
                model = model,
                prompt = user,
                systemPrompt = system,
                maxTokens = 1024
            )
            stripThinking(result.text)
        } finally {
            Llama.releaseModel(model)
        }
    }

    /** 去掉 Qwen3 的思考过程标签及内容，只保留最终回答。 */
    fun stripThinking(raw: String): String {
        var s = raw
        // 移除成对的 < thinking> ... </ thinking>
        s = Regex("(?is)<\\s*think\\s*>.*?</\\s*think\\s*>").replace(s, "")
        // 思考块未闭合时，从开始标签处截断
        val open = Regex("(?is)<\\s*think\\s*>").find(s)
        if (open != null) s = s.substring(0, open.range.first)
        // 去掉回答标签
        s = Regex("(?is)<\\s*/?\\s*response\\s*>").replace(s, "")
        return s.trim()
    }
}