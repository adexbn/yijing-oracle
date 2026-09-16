package com.yijing.app.core

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

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

    /** 下载源顺序：备用源优先，原站兜底。 */
    val SOURCES = listOf(MODEL_URL_MIRROR, MODEL_URL)

    /** 连接超时：TCP/TLS 握手阶段的等待上限。 */
    private const val CONNECT_TIMEOUT_MS = 20_000

    /**
     * 读超时：两次读到数据之间的最大间隔。
     *
     * 这里原来是 0（无限等待）。网络一抖，socket 看着还连着但一个字节都不来，
     * 进度条就会一直停着不动，要等系统 TCP 自己超时（可能十几分钟）才报失败，
     * 用户只能干等。改成 30 秒后，只要 30 秒内没收到任何数据就立刻失败，
     * 前端能马上给出「重试 / 取消」而不是假死。
     */
    private const val READ_TIMEOUT_MS = 30_000

    fun modelFile(context: Context): File =
        File(context.getExternalFilesDir("models"), MODEL_FILE)

    /**
     * 模型文件完整的最小字节数。Qwen3-1.7B Q4_K_M 约 1223MB。
     * 与 iOS 端同一套判断：不完整文件被 llama.cpp 用 mmap 加载时访问越界会直接崩（非可捕获异常），
     * 所以宁可按「长度偏小即视为未下载」处理。
     */
    private const val MIN_MODEL_BYTES = 1100L * 1024 * 1024

    fun isDownloaded(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() > MIN_MODEL_BYTES
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
     *
     * 可取消：调用方持有协程 Job 并 cancel 即可（见 OnboardingActivity 的「取消」按钮），
     * 取消会立刻断开 socket 并删除 .part 半成品，不会留下垃圾文件。
     */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (url in SOURCES) {
                try {
                    return@withContext downloadFrom(url, context, onProgress)
                } catch (e: CancellationException) {
                    throw e // 用户取消：直接冒泡，不要当成失败去试下一个源
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: Exception("下载失败")
        }

    private suspend fun downloadFrom(
        url: String,
        context: Context,
        onProgress: (Int) -> Unit,
    ): File {
        val dest = modelFile(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        tmp.delete() // 清理上一源留下的半成品

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) yijing-app")

        // 协程被 cancel 时立刻断开连接，让阻塞在 read() 的线程马上抛异常返回，
        // 而不是干等到 30 秒读超时才反应。
        val job: Job? = currentCoroutineContext()[Job]
        val disconnectOnCancel = job?.invokeOnCompletion { conn.disconnect() }

        var copied = 0L
        var expected = -1L
        var completed = false
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw Exception("下载失败 HTTP $code")
            }
            expected = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (expected > 0) {
                            onProgress(((copied * 100) / expected).toInt().coerceIn(0, 100))
                        }
                    }
                    output.flush()
                }
            }
            // 服务端给了长度就必须收满，否则算断流（半截文件会让 mmap 加载直接崩）
            if (expected > 0 && copied != expected) {
                throw Exception("下载中断（$copied / $expected 字节）")
            }
            completed = true
        } finally {
            disconnectOnCancel?.dispose()
            conn.disconnect()
            if (!completed) tmp.delete()
        }

        if (tmp.length() <= MIN_MODEL_BYTES) {
            tmp.delete()
            throw Exception("下载的文件不完整，请重试")
        }
        if (tmp.renameTo(dest)) return dest
        // rename 偶发失败时退化为复制
        tmp.copyTo(dest, overwrite = true)
        tmp.delete()
        return dest
    }
}