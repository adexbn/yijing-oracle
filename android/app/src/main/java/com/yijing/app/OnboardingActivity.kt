package com.yijing.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yijing.app.core.LocalAiClient
import com.yijing.app.core.ModelManager
import com.yijing.app.ui.BrushWritingView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次使用初始化页。
 *
 * 本地模型不随 APK 打包，所以第一次打开时：自动从国内镜像下载模型（约 1.2GB），
 * 下载完再把模型读进内存，之后进入主界面就能直接解卦、全程离线。
 * 模型存放在应用专属外部目录（/sdcard/Android/data/包名/files/models），
 * 覆盖安装、升级、卸载后重装都不会重复下载。
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        /**
         * 用户主动跳过初始化。
         * MainActivity 据此判断：模型没下载也不再把人推回初始化页，避免"卡死"在引导页。
         */
        @Volatile
        var skipped = false
    }

    private lateinit var brush: BrushWritingView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var percent: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryBtn: TextView

    /** 防止重复启动（重试、返回后又进入）。 */
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        brush = findViewById(R.id.onboardingBrush)
        headline = findViewById(R.id.onboardingHeadline)
        detail = findViewById(R.id.onboardingDetail)
        percent = findViewById(R.id.onboardingPercent)
        progressBar = findViewById(R.id.onboardingProgress)
        retryBtn = findViewById(R.id.onboardingRetry)

        brush.start()
        retryBtn.setOnClickListener { begin() }

        // 返回键 = 先跳过初始化直接进 App（模型日后可在设置里下载），不困在这一页。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (running) {
                    Toast.makeText(
                        this@OnboardingActivity,
                        "已跳过初始化，可稍后在应用内下载模型",
                        Toast.LENGTH_LONG
                    ).show()
                }
                skipped = true
                goMain()
            }
        })

        // 若之前已经下载好（例如升级安装），直接进主界面，不显示初始化页。
        if (ModelManager.isDownloaded(this)) {
            goMain()
            return
        }
        begin()
    }

    /** 走一遍「下载模型 → 载入内存 → 进主界面」。失败则留在本页给重试按钮。 */
    private fun begin() {
        if (running) return
        running = true

        retryBtn.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        percent.visibility = View.VISIBLE
        progressBar.progress = 0
        percent.text = "0%"
        headline.text = "首次使用需要初始化"
        detail.text = "正在准备解卦所需的本地模型"

        lifecycleScope.launch {
            try {
                if (!ModelManager.isDownloaded(this@OnboardingActivity)) {
                    detail.text = "正在从国内镜像下载模型（约 1.2GB）\n请保持网络畅通，仅需一次"
                    withContext(Dispatchers.IO) {
                        ModelManager.download(applicationContext) { pct ->
                            runOnUiThread { showProgress(pct) }
                        }
                    }
                }

                headline.text = "即将完成"
                detail.text = "正在把模型载入内存，稍候即可解卦"
                progressBar.progress = 100
                percent.text = "100%"
                withContext(Dispatchers.IO) {
                    LocalAiClient.preload(applicationContext)
                }

                goMain()
            } catch (e: Exception) {
                running = false
                headline.text = "初始化未完成"
                detail.text = buildString {
                    append(e.message ?: "下载失败")
                    append("\n请检查网络后重试，或改用 Wi-Fi 再试一次")
                }
                progressBar.visibility = View.GONE
                percent.visibility = View.GONE
                retryBtn.visibility = View.VISIBLE
            }
        }
    }

    /** 进度收尾：留 1% 给"载入内存"阶段，避免卡在 100% 让人以为死了。 */
    private fun showProgress(pct: Int) {
        val p = pct.coerceIn(0, 99)
        progressBar.progress = p
        percent.text = "$p%"
    }

    private fun goMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
