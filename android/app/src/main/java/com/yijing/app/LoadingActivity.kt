package com.yijing.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yijing.app.core.AiClient
import com.yijing.app.core.DivinationResult
import com.yijing.app.core.LocalAiClient
import com.yijing.app.core.PerfTrace
import com.yijing.app.core.YaoDb
import com.yijing.app.ui.BrushWritingView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 起卦等待页：卦象在点击起卦时已经算出，但本地/云端解读耗时较长。
 * 先在此等待解读完成，再带着完整结果进入结果页，避免"半成品先展示、再慢慢填满"。
 *
 * debug 包里这里也是性能观测点：整条链路（进页面 → 查表拼提示词 → 推理 → 跳转）
 * 都会写进 PerfTrace，供结果页的调试面板显示。
 */
class LoadingActivity : AppCompatActivity() {

    private lateinit var result: DivinationResult
    private var question = ""
    /** 非有效提问时由首页传入的软引导，非空则前置到系统提示词（与 iOS guardHint 一致）。 */
    private var guardHint = ""
    private var scope = ""
    private var enteredAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enteredAt = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)

        val r: DivinationResult? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("result", DivinationResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("result") as? DivinationResult
        }
        if (r == null) {
            finish()
            return
        }
        result = r
        question = intent.getStringExtra("question") ?: ""
        guardHint = intent.getStringExtra("guardHint") ?: ""

        // 复用首页「起卦」时开的会话，这样端到端计时从点起卦那一刻算起
        scope = PerfTrace.currentSession()
        PerfTrace.mark(scope, "页面可见（含 setContentView 与毛笔动画启动）",
            SystemClock.elapsedRealtime() - enteredAt,
            "机型核数 ${Runtime.getRuntime().availableProcessors()}")

        findViewById<BrushWritingView>(R.id.loadingBrush).start()
        startAi()
    }

    private fun startAi() {
        val tSync = SystemClock.elapsedRealtime()
        val (yaoYuan, _) = YaoDb.yao(result.original.name, result.movingLine)
        val cloudOn = AiClient.cloudEnabled(this) && AiClient.hasKey(this)
        if (cloudOn) {
            val config = AiClient.loadConfig(this)
            val (systemRaw, user) = AiClient.promptOf(
                question, result.original, result.changed, result.movingLine, yaoYuan
            )
            // 软引导前置到系统提示词（与 iOS CastFlow.runAiIfNeeded 一致）
            val system = if (guardHint.isNotEmpty()) guardHint + "\n" + systemRaw else systemRaw
            PerfTrace.mark(scope, "同步准备（查表+读配置+拼提示词）",
                SystemClock.elapsedRealtime() - tSync)
            val t0 = SystemClock.elapsedRealtime()
            Thread {
                try {
                    val reply = AiClient.request(config, system, user)
                    PerfTrace.mark(scope, "云端解卦耗时", SystemClock.elapsedRealtime() - t0)
                    runOnUiThread { gotoResult(reply, "大师解卦", "", "") }
                } catch (e: Exception) {
                    PerfTrace.mark(scope, "云端解卦失败", SystemClock.elapsedRealtime() - t0, e.message ?: "")
                    runOnUiThread { gotoResult("", "大师解卦", "大师解卦失败：${e.message}", "") }
                }
            }.start()
        } else {
            val hint = if (AiClient.cloudEnabled(this) && !AiClient.hasKey(this)) {
                "未配置 API Key，已改用本地模型"
            } else {
                ""
            }
            val (systemRaw, user) = LocalAiClient.promptOf(
                question, result.original, result.changed, result.movingLine, yaoYuan
            )
            // 软引导前置到系统提示词（与 iOS CastFlow.runAiIfNeeded 一致）
            val system = if (guardHint.isNotEmpty()) guardHint + "\n" + systemRaw else systemRaw
            PerfTrace.mark(scope, "同步准备（查表+读配置+拼提示词）",
                SystemClock.elapsedRealtime() - tSync,
                "爻辞 ${yaoYuan.length} 字 · user 提示词 ${user.length} 字")
            lifecycleScope.launch {
                val tDispatch = SystemClock.elapsedRealtime()
                PerfTrace.mark(scope, "到协程真正开跑（主线程排队）", tDispatch - enteredAt)
                val t0 = SystemClock.elapsedRealtime()
                try {
                    val stats = withContext(Dispatchers.IO) {
                        LocalAiClient.generate(applicationContext, system, user)
                    }
                    PerfTrace.mark(scope, "推理总计（含协程调度）", SystemClock.elapsedRealtime() - t0)
                    PerfTrace.mark(scope, "首字延迟与流式", 0, "当前 AAR 无流式回调，只统计到整体耗时")
                    gotoResult(stats.text, "解卦", "", hint)
                } catch (e: Exception) {
                    PerfTrace.mark(scope, "推理失败", SystemClock.elapsedRealtime() - t0, e.message ?: "")
                    gotoResult("", "解卦", "解卦失败：${e.message}", hint)
                }
            }
        }
    }

    private fun gotoResult(reply: String, mode: String, error: String, hint: String) {
        PerfTrace.mark(scope, "端到端：进入等待页 → 可以跳结果页",
            SystemClock.elapsedRealtime() - enteredAt)
        val intent = Intent(this, ResultActivity::class.java)
        intent.putExtra("result", result)
        intent.putExtra("question", question)
        intent.putExtra("aiReply", reply)
        intent.putExtra("aiMode", mode)
        intent.putExtra("aiError", error)
        intent.putExtra("aiHint", hint)
        intent.putExtra("aiPerf", PerfTrace.report(scope))
        startActivity(intent)
        finish()
    }
}
