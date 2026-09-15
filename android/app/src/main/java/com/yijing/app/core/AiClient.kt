package com.yijing.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 联网解读：OpenAI 兼容的 chat/completions 协议，覆盖 DeepSeek/OpenAI/通义/GLM。
 */
object AiClient {

    data class Config(
        val provider: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val cloudEnabled: Boolean
    )

    val PROVIDERS = mapOf(
        "DeepSeek" to ("https://api.deepseek.com" to "deepseek-chat"),
        "OpenAI" to ("https://api.openai.com/v1" to "gpt-4o-mini"),
        "通义千问" to ("https://dashscope.aliyuncs.com/compatible-mode/v1" to "qwen-plus"),
        "智谱 GLM" to ("https://open.bigmodel.cn/api/paas/v4" to "glm-4-flash"),
        "自定义" to ("" to "")
    )

    private const val PREFS = "yijing_settings"

    fun saveConfig(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("provider", config.provider)
            .putString("baseUrl", config.baseUrl)
            .putString("apiKey", Crypto.encrypt(config.apiKey))
            .putString("model", config.model)
            .putBoolean("cloudEnabled", config.cloudEnabled)
            .apply()
    }

    fun loadConfig(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val provider = p.getString("provider", "DeepSeek") ?: "DeepSeek"
        val (defUrl, defModel) = PROVIDERS[provider] ?: ("" to "")
        val storedKey = p.getString("apiKey", "") ?: ""
        // 优先按密文解密；解密失败（旧版本明文、密钥失效）时降级为原文。
        val apiKey = Crypto.decrypt(storedKey) ?: storedKey
        return Config(
            provider = provider,
            baseUrl = p.getString("baseUrl", defUrl) ?: defUrl,
            apiKey = apiKey,
            model = p.getString("model", defModel) ?: defModel,
            cloudEnabled = p.getBoolean("cloudEnabled", false)
        )
    }

    fun hasKey(context: Context): Boolean = loadConfig(context).apiKey.isNotBlank()

    fun cloudEnabled(context: Context): Boolean = loadConfig(context).cloudEnabled

    fun request(config: Config, system: String, user: String): String {
        val base = if (config.baseUrl.endsWith("/")) config.baseUrl.dropLast(1) else config.baseUrl
        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 60000

        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", system) })
                put(JSONObject().apply { put("role", "user"); put("content", user) })
            })
            put("temperature", 0.7)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw Exception("HTTP $code ${text.take(300)}")
        return JSONObject(text).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    /** 构造用于解读的爻辞上下文文本。 */
    fun promptOf(question: String, original: Hexagram, changed: Hexagram, movingLine: Int, yaoText: String): Pair<String, String> {
        val system = "你是精通《周易》的解卦师，结合卦象与用户所问给出简明、中肯的解读，不超过 180 字，用中文。"
        val user = buildString {
            append("所问：").append(question).append("\n")
            append("本卦：第").append(original.number).append("卦 ").append(original.name)
            append("（").append(original.symbol).append("）\n")
            append("变卦：第").append(changed.number).append("卦 ").append(changed.name)
            append("（").append(changed.symbol).append("）\n")
            append("动爻：第 ").append(movingLine).append(" 爻\n")
            append("动爻爻辞：").append(yaoText).append("\n")
            append("本卦卦辞：").append(original.judgment)
        }
        return system to user
    }
}