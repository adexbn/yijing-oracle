package com.yijing.app

import android.content.Context
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
import com.yijing.app.ui.TaijiProgressView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次使用初始化页。
 *
 * 本地模型不随 APK 打包，所以第一次打开时：自动下载模型（约 1.2GB，主站不通自动换备用源），
 * 下载完再把模型读进内存，之后进入主界面就能直接解卦、全程离线。
 * 模型存放在应用专属外部目录（/sdcard/Android/data/包名/files/models），
 * 覆盖安装、升级、卸载后重装都不会重复下载。
 *
 * 网络不稳时不想干等：下载中始终有「取消下载」，点击即时断开连接并清掉半成品；
 * 失败或取消后都有「重新尝试」和「先跳过，稍后再下载」两个出口，不会把人困在这一页。
 */
class OnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "yijing_onboarding"
        private const val KEY_SKIPPED = "skipped"

        /** 进程内缓存，省掉每次读 SP。 */
        @Volatile
        private var skippedInProcess = false

        /**
         * 用户是否已经主动跳过首次初始化。
         *
         * 这个标记必须是**持久化**的：MainActivity 每次 onCreate 都会据此判断是否把人推回本页，
         * 而本页一旦被 finish（点跳过、按返回），MainActivity 就是全新实例。
         * 只存在内存里的话，跳过 → 进主界面 → 立刻又被推回来 → 重新开始下载，
         * 用户点了取消再跳过还是同一圈，看上去就是"取消下载后卡在死循环"。
         */
        fun hasSkipped(context: Context): Boolean =
            skippedInProcess ||
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_SKIPPED, false)

        /** 记下"用户已跳过"，并落盘，之后任何一次冷启动都不再自动进本页。 */
        private fun markSkipped(context: Context) {
            skippedInProcess = true
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SKIPPED, true)
                .apply()
        }
    }

    private lateinit var taiji: TaijiProgressView
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var status: TextView
    private lateinit var percent: TextView
    private lateinit var progressBlock: View
    private lateinit var progressBar: ProgressBar
    private lateinit var retryBtn: TextView
    private lateinit var cancelBtn: TextView
    private lateinit var skipBtn: TextView

    /** 防止重复启动（重试、返回后又进入）。 */
    private var running = false

    /** 当前这次「下载 + 预载」的协程，点取消时 cancel 它。 */
    private var job: Job? = null

    /**
     * 轮次号。重试或取消后旧协程可能才刚抛异常回来，
     * 用它把过期回调挡掉，免得旧任务把新任务的界面状态改掉。
     */
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        taiji = findViewById(R.id.onboardingTaiji)
        headline = findViewById(R.id.onboardingHeadline)
        detail = findViewById(R.id.onboardingDetail)
        status = findViewById(R.id.onboardingStatus)
        percent = findViewById(R.id.onboardingPercent)
        progressBlock = findViewById(R.id.onboardingProgressBlock)
        progressBar = findViewById(R.id.onboardingProgress)
        retryBtn = findViewById(R.id.onboardingRetry)
        cancelBtn = findViewById(R.id.onboardingCancel)
        skipBtn = findViewById(R.id.onboardingSkip)

        taiji.start()
        retryBtn.setOnClickListener { begin() }
        cancelBtn.setOnClickListener { cancelDownload() }
        skipBtn.setOnClickListener { skipToMain() }

        // 返回键 = 先跳过初始化直接进 App（模型日后可在设置里下载），不困在这一页。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (running) {
                    job?.cancel()
                    Toast.makeText(
                        this@OnboardingActivity,
                        "已取消下载，可稍后在应用内下载模型",
                        Toast.LENGTH_LONG
                    ).show()
                }
                skipToMain()
            }
        })

        // 若之前已经下载好（例如升级安装），直接进主界面，不显示初始化页。
        if (ModelManager.isDownloaded(this)) {
            goMain()
            return
        }
        begin()
    }

    /** 太极的呼吸动画只在页面可见时跑，退到后台就停，别白耗电。 */
    override fun onStart() {
        super.onStart()
        taiji.start()
    }

    override fun onStop() {
        taiji.stop()
        super.onStop()
    }

    /** 走一遍「下载模型 → 载入内存 → 进主界面」。失败则留在本页给重试按钮。 */
    private fun begin() {
        if (running) return
        running = true
        val gen = ++generation

        retryBtn.visibility = View.GONE
        skipBtn.visibility = View.GONE
        cancelBtn.visibility = View.VISIBLE
        progressBlock.visibility = View.VISIBLE
        progressBar.progress = 0
        percent.text = "0%"
        status.text = "准备中"
        taiji.setProgress(0f)
        headline.text = "首次使用需要初始化"
        detail.text = "正在准备解卦所需的本地模型"

        job = lifecycleScope.launch {
            try {
                if (!ModelManager.isDownloaded(this@OnboardingActivity)) {
                    status.text = "下载中"
                    detail.text = "正在下载解卦模型（约 1.2GB）\n请保持网络畅通，仅需一次"
                    ModelManager.download(applicationContext) { pct ->
                        if (gen == generation) runOnUiThread { showProgress(pct) }
                    }
                }

                // 载入内存是 C 层调用，打断不了，所以先收回取消入口，免得点了没反应。
                cancelBtn.visibility = View.GONE
                headline.text = "即将完成"
                detail.text = "正在把模型载入内存，稍候即可解卦"
                status.text = "载入模型"
                progressBar.progress = 100
                percent.text = "100%"
                taiji.setProgress(1f)
                withContext(Dispatchers.IO) {
                    LocalAiClient.preload(applicationContext)
                }

                goMain()
            } catch (e: CancellationException) {
                showCancelled(gen)
                throw e
            } catch (e: Exception) {
                showFailed(gen, e.message ?: "下载失败")
            }
        }
    }

    /** 取消下载：立刻断开连接，不改「已跳过」状态，用户可重试或跳过。 */
    private fun cancelDownload() {
        if (!running) return
        detail.text = "正在取消…"
        job?.cancel()
    }

    private fun showCancelled(gen: Int) {
        if (gen != generation) return
        running = false
        job = null
        if (isFinishing || isDestroyed) return
        cancelBtn.visibility = View.GONE
        progressBlock.visibility = View.GONE
        status.text = "已取消"
        headline.text = "已取消下载"
        detail.text = "模型还没下载完。可以重新下载，或先跳过、稍后在设置里下载"
        retryBtn.visibility = View.VISIBLE
        skipBtn.visibility = View.VISIBLE
    }

    private fun showFailed(gen: Int, message: String) {
        if (gen != generation) return
        running = false
        job = null
        if (isFinishing || isDestroyed) return
        cancelBtn.visibility = View.GONE
        progressBlock.visibility = View.GONE
        status.text = "未完成"
        headline.text = "初始化未完成"
        detail.text = "$message\n请检查网络后重试，或改用 Wi-Fi 再试一次"
        retryBtn.visibility = View.VISIBLE
        skipBtn.visibility = View.VISIBLE
    }

    /** 进度收尾：留 1% 给"载入内存"阶段，避免卡在 100% 让人以为死了。 */
    private fun showProgress(pct: Int) {
        val p = pct.coerceIn(0, 99)
        progressBar.progress = p
        percent.text = "$p%"
        status.text = "下载中"
        // 圆环与太极同步生长，和 iOS 的 ringView 一致
        taiji.setProgress(p / 100f)
    }

    /**
     * 用户明确表示"先跳过"（点按钮或按返回键）。
     * 先把标记落盘再进主界面，否则 MainActivity 会立刻把本页又拉起来，形成死循环。
     */
    private fun skipToMain() {
        running = false
        job?.cancel()
        job = null
        markSkipped(this)
        goMain()
    }

    private fun goMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
