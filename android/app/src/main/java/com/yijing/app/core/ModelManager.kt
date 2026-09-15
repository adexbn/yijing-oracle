package com.yijing.app.core

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地小模型（GGUF）的下载与文件管理。
 * 模型不随 APK 打包，首次使用时按需下载到应用专属外部目录，或由用户从本地导入。
 */
object ModelManager {

    const val MODEL_FILE = "qwen3-1.7b-q4_k_m.gguf"
    const val MODEL_URL =
        "https://huggingface.co/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
    const val MODEL_URL_MIRROR =
        "https://hf-mirror.com/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"

    /** 下载源顺序：国内镜像优先，原版兜底。 */
    val SOURCES = listOf(MODEL_URL_MIRROR, MODEL_URL)

    fun modelFile(context: Context): File =
        File(context.getExternalFilesDir("models"), MODEL_FILE)

    fun isDownloaded(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > 100L * 1024 * 1024
    }

    /** 删除已下载模型，释放空间。 */
    fun delete(context: Context) {
        val f = modelFile(context)
        if (f.exists()) f.delete()
        val tmp = File(f.parentFile, f.name + ".part")
        if (tmp.exists()) tmp.delete()
    }

    /** 从本地流导入已下载好的模型文件（例如用户用电脑下载后拷贝到手机）。 */
    fun importFrom(context: Context, input: InputStream): File {
        val dest = modelFile(context)
        dest.parentFile?.mkdirs()
        input.use { it.copyTo(dest.outputStream()) }
        return dest
    }

    /**
     * 下载模型到本地。依次尝试所有下载源，任一源失败则换下一个。
     * 通过 [onProgress] 回调百分比进度（0..100）。
     * 先写入 .part 临时文件，成功后重命名为正式文件，避免半成品被判为可用。
     */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): File {
        var lastError: Exception? = null
        for (url in SOURCES) {
            try {
                return downloadFrom(url, context, onProgress)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: Exception("下载失败")
    }

    private fun downloadFrom(url: String, context: Context, onProgress: (Int) -> Unit): File {
        val dest = modelFile(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete() // 清理上一源留下的半成品

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 20000
        conn.readTimeout = 0
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) yijing-app")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw Exception("下载失败 HTTP $code")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0) onProgress(((copied * 100) / total).toInt())
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }

        if (tmp.renameTo(dest)) return dest
        // rename 偶发失败时退化为复制
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
        return dest
    }
}