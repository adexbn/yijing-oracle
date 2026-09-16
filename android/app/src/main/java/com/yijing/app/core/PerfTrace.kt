package com.yijing.app.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 性能埋点：回答「解卦到底慢在哪一段」，每个阶段单独记耗时：
 * 模型加载 → 等待就绪 → 预填充 prefill → 解码 decode → 后处理 → 页面跳转。
 *
 * **默认关闭**（[enabled] = false），关闭时所有入口都是空操作。
 * 需要排查时在隐藏入口里打开（结果页底部的调试面板 / 长按「我的」），
 * 选择记在 SharedPreferences 里、重启仍生效；release 包同样默认关闭。
 * 日志同时打到 Logcat（tag = YijingPerf）和外部私有目录下的 perf-trace.log。
 */
object PerfTrace {

    const val TAG = "YijingPerf"

    private const val PREFS = "yijing_perf"
    private const val KEY_ENABLED = "trace_enabled"
    private const val LOG_NAME = "perf-trace.log"
    private const val MAX_BYTES = 2L * 1024 * 1024

    /**
     * 埋点总开关，默认 false。
     *
     * 关掉的理由：每记一条都要拼字符串、写 Logcat、再把整行 append 进文件，
     * 其中好几处还是在主线程上；离线解卦本来就要等几十秒，没必要再为诊断信息付这份成本。
     * 另外 [attach] 里的 llama `getSystemInfo()` 会连带在冷启动时加载本地库，
     * 默认关闭后这段开销也一并省掉。
     */
    @Volatile
    var enabled: Boolean = false
        private set

    private val seq = AtomicInteger(0)
    private val attached = AtomicBoolean(false)
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var app: Context? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var currentId: String? = null

    private val lock = Any()
    private val order = ArrayList<String>()
    private val headers = HashMap<String, String>()
    private val sessions = HashMap<String, MutableList<Entry>>()

    data class Entry(val stage: String, val ms: Long, val detail: String)

    /** 进程启动时调用（见 YijingApp）。幂等。只读开关，默认不产生任何日志。 */
    fun attach(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        if (!attached.compareAndSet(false, true)) return
        val sp = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull()
        prefs = sp
        if (sp?.getBoolean(KEY_ENABLED, false) == true) enable()
    }

    /** 打开/关闭埋点，并记住这次选择。 */
    fun setEnabled(on: Boolean) {
        runCatching { prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply() }
        if (on) enable() else enabled = false
    }

    private fun enable() {
        if (enabled) return
        enabled = true
        val ctx = app ?: return
        runCatching {
            val f = logFile(ctx)
            if (f.exists() && f.length() > MAX_BYTES) f.delete()
        }
        // 补一条开头，后面写进来的阶段才有上下文可对照
        write("", "===== 开启埋点 =====", 0, deviceLine())
        runCatching {
            val info = dev.ffmpegkit.llama.Llama.getSystemInfo()
            write("", "llama.cpp system info", 0, info.replace("\n", " | "))
        }.onFailure { write("", "llama.cpp system info 读取失败", 0, it.message ?: "") }
    }

    fun deviceLine(): String {
        val cores = Runtime.getRuntime().availableProcessors()
        val rt = Runtime.getRuntime()
        val maxMb = rt.maxMemory() / 1048576
        val totalMb = totalRamMb()
        return "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} · " +
            "${cores} 核 · 堆上限 ${maxMb}MB · 物理内存 ${totalMb}MB · Android ${Build.VERSION.RELEASE}"
    }

    private fun totalRamMb(): Long = runCatching {
        val ra = android.app.ActivityManager.MemoryInfo()
        val am = app?.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getMemoryInfo(ra)
        ra.totalMem / 1048576
    }.getOrDefault(-1L)

    fun logFile(): File? {
        val ctx = app ?: return null
        return runCatching { logFile(ctx) }.getOrNull()
    }

    fun logFilePath(): String = logFile()?.absolutePath ?: "(尚未初始化)"

    private fun logFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, LOG_NAME)
    }

    /** 开一次新的追踪会话（一次解卦 / 一次预加载），返回会话 id。 */
    fun beginSession(label: String): String {
        if (!enabled) return ""
        val id = "#" + seq.incrementAndGet()
        currentId = id
        synchronized(lock) {
            if (!sessions.containsKey(id)) {
                sessions[id] = ArrayList()
                order.add(id)
            }
            headers[id] = label
        }
        write(id, "会话开始", 0, label)
        return id
    }

    fun currentSession(): String {
        if (!enabled) return ""
        return currentId ?: beginSession("未命名")
    }

    /** 手动计时器；用 [Span.end] 结束并自动记录。 */
    class Span internal constructor(private val id: String, private val stage: String) {
        private val t0 = SystemClock.elapsedRealtime()
        private var closed = false

        fun end(detail: String = ""): Long {
            if (closed) return 0
            closed = true
            val ms = SystemClock.elapsedRealtime() - t0
            mark(id, stage, ms, detail)
            return ms
        }
    }

    fun span(stage: String, id: String = currentSession()): Span = Span(id, stage)

    fun mark(stage: String, ms: Long, detail: String = "") = mark(currentSession(), stage, ms, detail)

    fun mark(scope: String, stage: String, ms: Long, detail: String = "") {
        if (!enabled) return
        synchronized(lock) {
            val list = sessions[scope] ?: ArrayList<Entry>().also {
                sessions[scope] = it
                order.add(scope)
            }
            list.add(Entry(stage, ms, detail))
        }
        write(scope, stage, ms, detail)
    }

    private fun write(scope: String, stage: String, ms: Long, detail: String) {
        val line = buildString {
            append('[').append(stamp.format(Date())).append(']')
            if (scope.isNotEmpty()) append(' ').append(scope)
            append(' ').append(stage)
            if (ms > 0) append(" = ").append(ms).append("ms")
            if (detail.isNotEmpty()) append("  ").append(detail)
        }
        Log.i(TAG, line)
        val ctx = app ?: return
        runCatching {
            val f = logFile(ctx)
            f.parentFile?.mkdirs()
            f.appendText(line + "\n")
        }
    }

    fun entries(scope: String): List<Entry> = synchronized(lock) {
        sessions[scope]?.toList() ?: emptyList()
    }

    /** 把一个会话渲染成可直接显示的短报告。 */
    fun report(scope: String): String {
        if (!enabled) return ""
        val list = entries(scope)
        if (list.isEmpty()) return ""
        val label = synchronized(lock) { headers[scope] } ?: "会话"
        val sb = StringBuilder()
        sb.append("【性能追踪 ").append(scope).append(" · ").append(label).append("】")
        for (e in list) {
            sb.append('\n').append("· ").append(e.stage).append('：').append(fmt(e.ms))
            if (e.detail.isNotEmpty()) sb.append('（').append(e.detail).append('）')
        }
        return sb.toString()
    }

    fun fmt(ms: Long): String =
        if (ms >= 1000) String.format(Locale.US, "%.2f s", ms / 1000.0) else "${ms} ms"

    /** 最近一次会话的报告，用于「设置」里查看历史。 */
    fun lastReport(): String {
        val id = synchronized(lock) { order.lastOrNull() } ?: return ""
        return report(id)
    }

    fun allText(): String {
        if (!enabled) return ""
        val ids = synchronized(lock) { order.toList() }
        return ids.joinToString("\n\n") { report(it) }
    }

    fun clear() {
        synchronized(lock) {
            order.clear()
            headers.clear()
            sessions.clear()
        }
        currentId = null
        val f = logFile() ?: return
        runCatching { f.delete() }
    }
}
