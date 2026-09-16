package com.yijing.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.yijing.app.R
import com.yijing.app.core.LocalAiClient
import com.yijing.app.core.ModelManager
import com.yijing.app.core.PerfTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatActivity

/**
 * 诊断面板：看解卦每一段的耗时、复制报告、把完整日志文件分享出去。
 *
 * 入口藏在设置页最下面的「日志与诊断」里（设置页本身也要长按首页「我的」才进得来），
 * 界面上看不出有这些东西，不影响普通用户；发给测试同学时让他们在这里把日志发回来即可。
 * 面板里还有基准套件，用于实机对比"思考开关/线程数"这类参数。
 */
object PerfPanel {

    private const val BENCH_IDLE = "跑基准套件（4 组各 96 token，约 2 分钟，跑完全部日志自动进剪贴板）"

    /**
     * 把面板挂到 [container] 里；不传则退回结果页的根容器。
     * 面板整体（含开关、报告、按钮）都是运行时拼的，布局文件里只需要一个空容器。
     */
    fun attach(activity: AppCompatActivity, report: String, container: LinearLayout? = null) {
        val root = container
            ?: activity.findViewById<LinearLayout>(R.id.resultRoot)
            ?: return
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F3EDE1"))
            setPadding(px(14), px(12), px(14), px(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(12) }
        }

        card.addView(TextView(activity).apply {
            text = "⚙ 性能追踪（默认关闭）"
            setTextColor(Color.parseColor("#8C2B22"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })

        // 开关放在最前面：埋点默认不启用，想抓数据时点一下即可（选择会记住）
        val toggle = TextView(activity).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(px(12), px(8), px(12), px(8))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8) }
        }
        fun renderToggle() {
            val on = PerfTrace.enabled
            toggle.text = if (on) "埋点已开启 · 点此关闭" else "埋点已关闭 · 点此开启"
            toggle.setTextColor(Color.parseColor(if (on) "#FFFFFF" else "#8C2B22"))
            toggle.setBackgroundColor(Color.parseColor(if (on) "#8C2B22" else "#E6DCC9"))
        }
        renderToggle()
        toggle.setOnClickListener {
            PerfTrace.setEnabled(!PerfTrace.enabled)
            renderToggle()
            toast(
                activity,
                if (PerfTrace.enabled) "已开启埋点，下一次解卦开始记录" else "已关闭埋点，不再写任何日志"
            )
        }
        card.addView(toggle)

        // 设备与配置：判断「6.59 tok/s 到底是机器不行还是配置没给够」的第一依据
        card.addView(TextView(activity).apply {
            text = "${PerfTrace.deviceLine()}\n${LocalAiClient.describeRuntime()}"
            setTextColor(Color.parseColor("#6B6B6B"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, px(8), 0, 0)
            setTextIsSelectable(true)
        })

        val reportView = TextView(activity).apply {
            text = report.ifBlank { "埋点未开启，本次没有记录。点上面的开关即可打开再解一卦。" }
            setTextColor(Color.parseColor("#2B2B2B"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, px(8), 0, px(8))
            setTextIsSelectable(true)
        }
        card.addView(reportView)

        card.addView(TextView(activity).apply {
            text = "日志文件：${PerfTrace.logFilePath()}\nLogcat tag：${PerfTrace.TAG}\nadb logcat -s ${PerfTrace.TAG}"
            setTextColor(Color.parseColor("#6B6B6B"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
        })

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(10), 0, 0)
        }

        fun button(label: String, onClick: (View) -> Unit) = TextView(activity).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#8C2B22"))
            textSize = 13f
            setPadding(px(12), px(8), px(12), px(8))
            setBackgroundColor(Color.parseColor("#E6DCC9"))
            isClickable = true
            setOnClickListener(onClick)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = px(8) }
        }

        row.addView(button("复制报告") {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("yijing-perf", report))
            toast(activity, "性能报告已复制")
        })

        row.addView(button("复制全部日志") {
            activity.lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) { PerfTrace.allText() }
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("yijing-perf", text))
                toast(activity, "全部日志已复制（${text.length} 字）")
            }
        })

        row.addView(button("分享日志文件") { share(activity) })

        card.addView(row)

        // 基准套件：同一段提示词换开关各跑一轮，直接回答「思考吃掉多少时间」「线程还有没有空间」
        val bench = TextView(activity).apply {
            text = BENCH_IDLE
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 13f
            setPadding(px(12), px(10), px(12), px(10))
            setBackgroundColor(Color.parseColor("#8C2B22"))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(10) }
        }
        bench.setOnClickListener {
            // 基准套件必须记数据，所以先替用户把埋点打开
            if (!PerfTrace.enabled) {
                PerfTrace.setEnabled(true)
                renderToggle()
            }
            bench.isEnabled = false
            bench.text = "基准套件进行中（要换线程重载模型，约 2 分钟）…"
            activity.lifecycleScope.launch {
                val r = runCatching {
                    withContext(Dispatchers.IO) {
                        ensureModelReady(activity)
                        LocalAiClient.benchSuite(activity.applicationContext, 96)
                    }
                }
                bench.isEnabled = true
                bench.text = BENCH_IDLE
                val report = r.getOrNull()
                val err = r.exceptionOrNull()
                when {
                    err != null -> toast(activity, "基准套件失败：${err.message}")
                    report == null || report.rows.isEmpty() ->
                        toast(activity, "基准套件没跑出结果，先到设置页确认模型已下载")
                    else -> {
                        reportView.text = PerfTrace.report(report.scope)
                        // 直接把整份日志塞进剪贴板，用户粘回来即可
                        val text = withContext(Dispatchers.IO) { PerfTrace.allText() }
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("yijing-perf", text))
                        val summary = report.rows.joinToString("\n") { row ->
                            "${row.label}（${row.threads} 线程）：${row.stats.tokens} token · " +
                                "${"%.2f".format(row.stats.tokensPerSecond)} tok/s · " +
                                "decode ${PerfTrace.fmt(row.stats.decodeMs)}"
                        }
                        toast(activity, "$summary\n\n整份日志已复制到剪贴板，直接粘回来即可")
                    }
                }
            }
        }
        card.addView(bench)

        // 清空
        val clear = TextView(activity).apply {
            text = "清空日志"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#6B6B6B"))
            textSize = 12f
            setPadding(px(12), px(8), px(12), px(8))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(6) }
        }
        clear.setOnClickListener {
            PerfTrace.clear()
            toast(activity, "日志已清空")
        }
        card.addView(clear)

        root.addView(card)
    }

    /** 面板里的基准测试需要模型已下载，否则给出明确提示。 */
    private suspend fun ensureModelReady(context: Context) {
        if (!ModelManager.isDownloaded(context)) {
            throw IllegalStateException("本地模型尚未下载，请先到设置页下载")
        }
    }

    private fun share(activity: AppCompatActivity) {
        val file = PerfTrace.logFile()
        if (file == null || !file.exists()) {
            toast(activity, "还没有日志文件")
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "yijing-perf-trace")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, "分享性能日志"))
        }.onFailure { toast(activity, "分享失败：${it.message}") }
    }

    private fun toast(activity: AppCompatActivity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }
}
