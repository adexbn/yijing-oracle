package com.yijing.app.core

import android.content.Context
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 本地小模型的下载与文件管理。
 *
 * 引擎 = MNN 3.6.1，模型 = Qwen3.5-2B（non-thinking），「一个目录 + 8 个文件」：
 *
 * | 文件 | 字节数 | 作用 |
 * | --- | --- | --- |
 * | `config.json` | 652 | 模型元信息；引擎用它所在目录推断 `base_dir` |
 * | `llm_config.json` | 8 692 | MNN 自有配置（隐藏层、层数、jinja 聊天模板、eos） |
 * | `llm.mnn` | 2 148 136 | LLM 计算图结构 |
 * | `llm.mnn.json` | 5 344 018 | LLM 额外配置（Qwen3.5 新型号带的文件） |
 * | `tokenizer.txt` | 6 465 727 | 分词器词表 |
 * | `visual.mnn` | 488 096 | 视觉编码器计算图（多模态模型必需，纯文本也得加载） |
 * | `visual.mnn.weight` | 195 587 264 | 视觉编码器权重 |
 * | `llm.mnn.weight` | 1 176 647 702 | LLM 权重（占 85.3%） |
 *
 * 字节数取自官方仓库的 LFS 元数据（HuggingFace `taobao-mnn/Qwen3.5-2B-MNN`），
 * 下载后按**精确长度**逐个核对：少了会崩、多了说明拿到的不是同一份文件。
 *
 * 模型不随 APK 打包，首次使用时按需下载到应用专属外部目录，或由用户导入 zip。
 */
object ModelManager {

    /** 模型目录名，放在 `getExternalFilesDir("models")` 下面。 */
    const val MODEL_DIR_NAME = "qwen3.5-2b-mnn"

    /** 模型清单里的一个文件。 */
    data class ModelFile(val name: String, val bytes: Long) {
        /** 人类可读体积，用于提示文案。 */
        val sizeText: String get() = formatSize(bytes)
    }

    /**
     * 必需文件清单。顺序刻意「小的在前、大的在后」：
     * 前 5 个加起来才约 13MB，先跑完能立刻暴露网络/证书问题，
     * 不至于让用户等完 1.1GB 才发现根本连不上。
     */
    val FILES: List<ModelFile> = listOf(
        ModelFile("config.json", 652L),
        ModelFile("llm_config.json", 8_692L),
        ModelFile("llm.mnn", 2_148_136L),
        ModelFile("llm.mnn.json", 5_344_018L),
        ModelFile("visual.mnn", 488_096L),
        ModelFile("tokenizer.txt", 6_465_727L),
        ModelFile("visual.mnn.weight", 195_587_264L),
        ModelFile("llm.mnn.weight", 1_176_647_702L)
    )

    /** 全套文件的字节数合计（1,386,690,287 B ≈ 1.29 GiB），下载进度按它归一化。 */
    val TOTAL_BYTES: Long = FILES.sumOf { it.bytes }

    /**
     * 模型仓库页面（供用户用电脑手动下载）。
     * 链接给的是**仓库页**而不是某个文件：MNN 模型是 6 个文件成套使用的，
     * 只下单个文件装不起来。
     */
    const val MODEL_URL = "https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/tree/main"
    const val MODEL_URL_MIRROR = "https://modelscope.cn/models/MNN/Qwen3.5-2B-MNN/files"

    /** 两个直连下载源的 URL 前缀，文件名直接拼在后面。 */
    private const val BASE_HF_MIRROR =
        "https://hf-mirror.com/taobao-mnn/Qwen3.5-2B-MNN/resolve/main/"
    private const val BASE_MODELSCOPE =
        "https://modelscope.cn/models/MNN/Qwen3.5-2B-MNN/resolve/master/"

    /** 下载源顺序：镜像优先，ModelScope 兜底。两个源都已逐文件验证可直连。 */
    val SOURCES = listOf(BASE_HF_MIRROR, BASE_MODELSCOPE)

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

    /** 应用专属目录；外置存储不可用时退回内部存储（否则模型无处安放）。 */
    private fun baseDir(context: Context): File =
        context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    /** 模型目录。 */
    fun modelDir(context: Context): File = File(baseDir(context), MODEL_DIR_NAME)

    /** 引擎入口：`config.json` 的绝对路径（`base_dir` 由它推断）。 */
    fun configPath(context: Context): String =
        File(modelDir(context), "config.json").absolutePath

    /** 清单里某个文件的目标路径。 */
    fun fileOf(context: Context, name: String): File = File(modelDir(context), name)

    /**
     * 现有文件的长度与清单不符的项。空表示齐活。
     *
     * 只看长度就够：这几个文件在官方仓库里是定长产物，长度对得上就意味着内容完整
     * （截断、串源、半成品都会体现在长度上）。SHA256 更严格，但 1.2GB 在手机上算一遍
     * 要好几秒，不划算。
     *
     * 例外：`llm_config.json` 如果已经打过运行时补丁（`.llm_config_patched` 标记存在），
     * 就跳过精确长度检查——补丁会改文件内容，但不影响功能正确性。
     */
    fun missingOrMismatched(context: Context): List<ModelFile> {
        val patched = File(modelDir(context), ".llm_config_patched").exists()
        return FILES.filter { spec ->
            if (patched && spec.name == "llm_config.json") {
                // 打过补丁：只检查文件存在，不校验长度
                !File(modelDir(context), spec.name).exists()
            } else {
                val f = File(modelDir(context), spec.name)
                !f.exists() || f.length() != spec.bytes
            }
        }
    }

    /** 5 个文件是否都在且长度精确。 */
    fun isDownloaded(context: Context): Boolean = missingOrMismatched(context).isEmpty()

    /** 已落盘的有效字节数（只统计长度正确的文件），用于「已下载 1.1GB」这类文案。
     *
     * `llm_config.json` 打过补丁后长度会变，这里按清单里的标称长度算，
     * 避免进度条出现诡异的微小波动。
     */
    fun downloadedBytes(context: Context): Long =
        FILES.filter { spec ->
            val f = File(modelDir(context), spec.name)
            if (spec.name == "llm_config.json") {
                f.exists() // 存在就算合格（补丁过的也认）
            } else {
                f.exists() && f.length() == spec.bytes
            }
        }.sumOf { it.bytes }

    /** 人类可读体积（1024 进制，保留一位小数）。 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.2fGB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1fMB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.1fKB".format(bytes.toDouble() / (1L shl 10))
        else -> "${bytes}B"
    }

    /** 诊断文案：缺了哪个、期望多少、实际多少。全齐时返回 null。 */
    fun verifyMessage(context: Context): String? {
        val bad = missingOrMismatched(context) ?: return null
        if (bad.isEmpty()) return null
        return bad.joinToString("；") { spec ->
            val f = File(modelDir(context), spec.name)
            val actual = if (f.exists()) f.length() else -1L
            "${spec.name} 期望 ${spec.bytes} 字节，实际 ${if (actual < 0) "缺失" else "$actual 字节"}"
        }
    }

    /** 删除模型目录（含半成品临时文件），释放空间。 */
    fun delete(context: Context) {
        modelDir(context).deleteRecursively()
        importDir(context).deleteRecursively()
    }

    // ------------------------------------------------------------------ llm_config 补丁
    /** llm_config.json 补丁版本号。补丁逻辑更新后 +1，App 会自动用 .orig 重新打。 */
    private const val PATCH_VERSION = 2

    /**
     * 给 `llm_config.json` 打运行时补丁，确保纯文本推理能跑通。
     *
     * Qwen3.5-2B-MNN 是多模态模型（`is_visual=true`、带视觉分支），
     * 但我们只用纯文本能力。补丁内容：
     *
     * 1. `is_visual` → false（禁用视觉分支，MNN 就不去加载 visual.mnn）
     * 2. 移除 `image_mean` / `image_norm` / `image_size` / `vision_start` /
     *    `vision_end` / `image_pad` / `num_grid_per_side` / `has_deepstack`
     *    这些视觉相关字段（避免引擎读到误触发视觉路径）
     *
     * 注意：**不要** 改 `is_mrope`。Qwen3.5 的 RoPE 位置编码就是按多模态格式排布权重的，
     * 硬改成 false 会导致 `Reshape error: 9344 -> 9312`。纯文本推理 mrope 也能正常工作。
     *
     * 补丁是幂等的：打过了（且版本一致）就直接返回。原始文件备份成 `llm_config.json.orig`。
     *
     * @return true 表示补丁已生效（刚打的或之前打过），false 表示文件不存在或补丁失败
     */
    fun ensurePatchedLlmConfig(context: Context): Boolean {
        val dir = modelDir(context)
        val configFile = File(dir, "llm_config.json")
        val backupFile = File(dir, "llm_config.json.orig")
        val markerFile = File(dir, ".llm_config_patched")

        if (!configFile.exists()) return false

        // 检查补丁版本：版本对不上就从备份恢复，重新打
        val currentVersion = try {
            if (markerFile.exists()) markerFile.readText().trim().toIntOrNull() else null
        } catch (_: Exception) {
            null
        }
        if (currentVersion == PATCH_VERSION) return true

        return try {
            // 有备份就从备份恢复（保证补丁基于原始文件），没有就用当前文件当原始
            val sourceFile = if (backupFile.exists()) {
                backupFile.copyTo(configFile, overwrite = true)
                backupFile
            } else {
                // 第一次打，先备份当前文件
                configFile.copyTo(backupFile, overwrite = false)
                configFile
            }

            val json = JSONObject(sourceFile.readText(Charsets.UTF_8))

            // 1. 关掉视觉分支
            json.put("is_visual", false)

            // 2. 移除视觉相关字段
            val visualFields = arrayOf(
                "image_mean", "image_norm", "image_size",
                "vision_start", "vision_end", "image_pad",
                "num_grid_per_side", "has_deepstack"
            )
            for (field in visualFields) {
                json.remove(field)
            }

            // 3. 写回 + 写版本标记
            configFile.writeText(json.toString(4), Charsets.UTF_8)
            markerFile.writeText(PATCH_VERSION.toString(), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 导入 zip 时的解包暂存目录，跟模型目录同级。 */
    private fun importDir(context: Context): File =
        File(baseDir(context), "$MODEL_DIR_NAME.import")

    /**
     * 从本地流导入模型：接受一个包含上述 5 个文件的 **zip**。
     *
     * 换成 zip 的原因很直接：MNN 模型是 5 个文件成套使用，`llm.mnn.weight` 单独就 1.2GB，
     * 旧的「选一个 GGUF 文件」那套在这里没有对应物，逐个选 5 次既不现实也容易选错版本。
     *
     * 流程刻意做成「先全部解到暂存目录 → 逐个核长度 → 全部合格才替换正式目录」：
     * 中途失败不会把原来能用的模型毁掉。
     *
     * @return 模型目录。
     * @throws Exception 包内找不到清单文件、或文件长度不符时抛出，message 里带明细。
     */
    fun importFrom(context: Context, input: InputStream): File {
        val staging = importDir(context)
        staging.deleteRecursively()
        if (!staging.mkdirs() && !staging.isDirectory) {
            throw Exception("无法创建解包目录：${staging.absolutePath}")
        }

        var extracted = 0
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    // 允许压缩包里有层级目录：按文件名匹配清单。
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    val spec = FILES.firstOrNull { it.name == name } ?: continue
                    File(staging, spec.name).outputStream().use { out -> zip.copyTo(out) }
                    extracted++
                }
            }
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw Exception("解压失败：${e.message}")
        }

        if (extracted == 0) {
            staging.deleteRecursively()
            throw Exception(
                "压缩包里没有 MNN 模型文件，应包含：" + FILES.joinToString("、") { it.name }
            )
        }

        val bad = FILES.filter { spec ->
            val f = File(staging, spec.name)
            !f.exists() || f.length() != spec.bytes
        }
        if (bad.isNotEmpty()) {
            val detail = bad.joinToString("；") { spec ->
                val f = File(staging, spec.name)
                val actual = if (f.exists()) f.length() else -1L
                "${spec.name} 期望 ${spec.bytes}，实际 ${if (actual < 0) "缺失" else actual.toString()}"
            }
            staging.deleteRecursively()
            throw Exception("包内文件不完整或不是同一版本：$detail")
        }

        val dest = modelDir(context)
        dest.deleteRecursively()
        if (!dest.mkdirs() && !dest.isDirectory) {
            staging.deleteRecursively()
            throw Exception("无法创建模型目录：${dest.absolutePath}")
        }
        FILES.forEach { spec ->
            val from = File(staging, spec.name)
            val to = File(dest, spec.name)
            if (!from.renameTo(to)) {
                from.copyTo(to, overwrite = true)
                from.delete()
            }
        }
        staging.deleteRecursively()
        return dest
    }

    /**
     * 下载整套模型到本地。
     *
     * 逐文件下载，单文件内部先写 `<名字>.part` 再改名，所以任何时刻磁盘上
     * 要么是合格文件、要么是会被下次启动清掉的半成品，不存在「半截文件被当成可用」。
     *
     * 两个源依次尝试；同一个源内会**跳过已合格的文件**，所以断在第 4 个文件后重试
     * 只需补 1 个，不必重下 1.2GB。
     *
     * @param onProgress 百分比 0..100，按字节总量算（只算清单内的文件）。
     *
     * 可取消：调用方持有协程 Job 并 cancel 即可（见 OnboardingActivity 的「取消」按钮），
     * 取消会立刻断开 socket 并删除 .part 半成品，不会留下垃圾文件。
     */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = modelDir(context)
            if (!dir.mkdirs() && !dir.isDirectory) {
                throw Exception("无法创建模型目录：${dir.absolutePath}")
            }
            var lastError: Exception? = null
            for (base in SOURCES) {
                try {
                    return@withContext downloadAll(base, context, onProgress)
                } catch (e: CancellationException) {
                    throw e // 用户取消：直接冒泡，不要当成失败去试下一个源
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: Exception("下载失败")
        }

    private suspend fun downloadAll(
        baseUrl: String,
        context: Context,
        onProgress: (Int) -> Unit
    ): File {
        // 已完成的部分先算进进度，否则换源重试时进度条会从 0 重来，看着像白干了。
        var done = downloadedBytes(context)
        onProgress(((done * 100) / TOTAL_BYTES).toInt().coerceIn(0, 100))

        // 先清掉上一个源留下的半成品，避免和新一轮的 .part 混淆。
        FILES.forEach { File(modelDir(context), it.name + ".part").delete() }

        for (spec in FILES) {
            val dest = File(modelDir(context), spec.name)
            if (dest.exists() && dest.length() == spec.bytes) {
                continue // 这个文件已经是对的，跳过
            }
            val tmp = File(modelDir(context), spec.name + ".part")
            tmp.delete()
            downloadOne(baseUrl + spec.name, spec, tmp, done) { copied ->
                onProgress((((done + copied) * 100) / TOTAL_BYTES).toInt().coerceIn(0, 100))
            }
            if (tmp.length() != spec.bytes) {
                tmp.delete()
                throw Exception("${spec.name} 长度不符（${tmp.length()} / ${spec.bytes} 字节），已丢弃")
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            done += spec.bytes
            onProgress(((done * 100) / TOTAL_BYTES).toInt().coerceIn(0, 100))
        }

        val stillBad = missingOrMismatched(context)
        if (stillBad.isNotEmpty()) {
            throw Exception("下载完成但校验未通过：" + stillBad.joinToString("、") { it.name })
        }
        return modelDir(context)
    }

    private suspend fun downloadOne(
        url: String,
        spec: ModelFile,
        tmp: File,
        alreadyDone: Long,
        onFileProgress: (Long) -> Unit
    ) {
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
                throw Exception("${spec.name} 下载失败 HTTP $code")
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
                        onFileProgress(copied)
                    }
                    output.flush()
                }
            }
            // 服务端给了长度就必须收满，否则算断流（半截模型加载时会崩）
            if (expected > 0 && copied != expected) {
                throw Exception("${spec.name} 下载中断（$copied / $expected 字节）")
            }
            completed = true
        } finally {
            disconnectOnCancel?.dispose()
            conn.disconnect()
            if (!completed) tmp.delete()
        }
        if (spec.bytes != copied) {
            // 长度对不上就直接失败，别留给上层去猜；服务端内容变了也走这条。
            throw Exception("${spec.name} 长度不符（收到 $copied，期望 ${spec.bytes} 字节）")
        }
    }
}
