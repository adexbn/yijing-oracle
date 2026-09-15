package com.yijing.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yijing.app.core.AiClient
import com.yijing.app.core.DivinationResult
import com.yijing.app.core.LocalAiClient
import com.yijing.app.core.YaoDb
import com.yijing.app.ui.BrushWritingView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 起卦等待页：卦象在点击起卦时已经算出，但本地/云端解读耗时较长。
 * 先在此等待解读完成，再带着完整结果进入结果页，避免"半成品先展示、再慢慢填满"。
 */
class LoadingActivity : AppCompatActivity() {

    private lateinit var result: DivinationResult
    private var question = ""

    override fun onCreate(savedInstanceState: Bundle?) {
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

        findViewById<BrushWritingView>(R.id.loadingBrush).start()
        startAi()
    }

    private fun startAi() {
        val (yaoYuan, _) = YaoDb.yao(result.original.name, result.movingLine)
        val cloudOn = AiClient.cloudEnabled(this) && AiClient.hasKey(this)
        if (cloudOn) {
            val config = AiClient.loadConfig(this)
            val (system, user) = AiClient.promptOf(
                question, result.original, result.changed, result.movingLine, yaoYuan
            )
            Thread {
                try {
                    val reply = AiClient.request(config, system, user)
                    runOnUiThread { gotoResult(reply, "大师解卦", "", "") }
                } catch (e: Exception) {
                    runOnUiThread { gotoResult("", "大师解卦", "大师解卦失败：${e.message}", "") }
                }
            }.start()
        } else {
            val hint = if (AiClient.cloudEnabled(this) && !AiClient.hasKey(this)) {
                "未配置 API Key，已改用本地模型"
            } else {
                ""
            }
            val (system, user) = LocalAiClient.promptOf(
                question, result.original, result.changed, result.movingLine, yaoYuan
            )
            lifecycleScope.launch {
                try {
                    val reply = withContext(Dispatchers.IO) {
                        LocalAiClient.generate(applicationContext, system, user)
                    }
                    gotoResult(reply, "解卦", "", hint)
                } catch (e: Exception) {
                    gotoResult("", "解卦", "解卦失败：${e.message}", hint)
                }
            }
        }
    }

    private fun gotoResult(reply: String, mode: String, error: String, hint: String) {
        val intent = Intent(this, ResultActivity::class.java)
        intent.putExtra("result", result)
        intent.putExtra("question", question)
        intent.putExtra("aiReply", reply)
        intent.putExtra("aiMode", mode)
        intent.putExtra("aiError", error)
        intent.putExtra("aiHint", hint)
        startActivity(intent)
        finish()
    }
}