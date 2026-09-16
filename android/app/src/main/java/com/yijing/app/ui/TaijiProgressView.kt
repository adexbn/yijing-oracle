package com.yijing.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.min

/**
 * 首次初始化页的「太极 + 环形进度」，与 iOS 端 OnboardingView.ringView / TaijiMark 一一对应。
 *
 * 为什么要单独做这个自绘 View：iOS 在等待模型下载时是「太极缓缓呼吸 + 朱砂圆环随进度生长」，
 * 一眼能看出程序在动、也看得出下载到哪了；Android 之前只有一个静态的「爻」字，
 * 看上去像是卡住了。
 *
 * 画法（自下而上）：
 * 1. 纸色圆盘，外圈一圈极淡的品牌色柔光（替代 iOS 的 shadow）；
 * 2. 分隔线色的细圆环，代表「还没走完」的部分；
 * 3. 朱砂色进度弧，从正上方顺时针生长；
 * 4. 太极标记：右半为墨、左半为纸，上下双鱼头 + 双鱼眼，最外一圈淡墨勾边；
 *    整体以 3.2 秒一次的节奏在 0.94~1.0 之间缓慢呼吸。
 *
 * 进度更新带缓动，避免下载回调密集时圆环一跳一跳。
 */
class TaijiProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val inkColor = Color.parseColor("#201D17")
    private val paperColor = Color.parseColor("#FBF8F2")
    private val brandColor = Color.parseColor("#8A6D3B")
    private val cinnabarColor = Color.parseColor("#A63A2B")
    private val dividerColor = Color.parseColor("#E4DED1")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()
    private val halfPath = Path()

    /** 外部设定的目标进度（0~1）。 */
    private var target = 0f

    /** 实际画出来的进度，向 [target] 缓动（对应 iOS 的 .animation(.easeOut(0.28))）。 */
    private var drawn = 0f

    /** 入场：0→1，整体淡入并轻微放大（对应 iOS 的 opacity / scaleEffect 0.9→1）。 */
    private var entrance = 0f

    /** 呼吸：0→1 往返（对应 iOS 的 repeatForever(autoreverses: true)，单程 3.2 秒）。 */
    private var breath = 0f

    private var started = false

    private val entranceAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            entrance = it.animatedValue as Float
            invalidate()
        }
    }

    private val breathAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            breath = it.animatedValue as Float
            invalidate()
        }
    }

    /** 开始动画（入场 + 呼吸）。重复调用无副作用。 */
    fun start() {
        started = true
        entranceAnim.cancel()
        entranceAnim.start()
        breathAnim.cancel()
        breathAnim.start()
    }

    /** 停止动画。页面不可见时调用，避免后台空转耗电。 */
    fun stop() {
        entranceAnim.cancel()
        breathAnim.cancel()
    }

    /** 设置下载进度（0~1）。 */
    fun setProgress(value: Float) {
        val v = value.coerceIn(0f, 1f)
        if (abs(v - target) < 0.0005f) return
        target = v
        postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 忘了调 start() 也不会白屏，进来就自己动。
        if (!started) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fallback = dp(172f).toInt()
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else {
            fallback
        }
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            fallback
        }
        val s = min(w, h)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f

        // 进度缓动：每帧向目标靠拢，约 260ms 收敛（等价于 iOS 的 easeOut(0.28)）
        val delta = target - drawn
        if (abs(delta) > 0.0005f) {
            drawn += delta * 0.18f
            postInvalidateOnAnimation()
        } else {
            drawn = target
        }

        val alpha = entrance.coerceIn(0f, 1f)
        // 留出边距给外圈柔光，否则会被 View 边界裁掉
        val r = size / 2f - dp(6f)

        canvas.save()
        val scale = 0.9f + 0.1f * alpha
        canvas.scale(scale, scale, cx, cy)

        // 1) 圆盘外的柔光
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy + dp(5f), r + dp(12f),
            intArrayOf(Color.argb((31 * alpha).toInt(), 0x8A, 0x6D, 0x3B), Color.TRANSPARENT),
            floatArrayOf(0.84f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy + dp(5f), r + dp(12f), paint)
        paint.shader = null

        // 2) 纸色圆盘（半透明，让背后的宣纸渐变透出来）
        paint.color = Color.WHITE
        paint.alpha = (255 * 0.75f * alpha).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, paint)

        // 3) 未完成部分的分隔线圆环
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = dividerColor
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r, paint)

        // 4) 朱砂进度弧：从正上方顺时针生长；0 时也留一点点，让人看得出有这一圈
        val p = drawn.coerceAtLeast(0.004f)
        paint.strokeWidth = dp(3f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = cinnabarColor
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(arcRect, -90f, 360f * p, false, paint)

        // 5) 太极：呼吸幅度 0.94 ~ 1.0
        val taijiR = r * (108f / 172f) * (0.94f + 0.06f * breath)
        drawTaiji(canvas, cx, cy, taijiR, alpha)

        canvas.restore()
    }

    /** 太极标记：纸色为阳、墨色为阴，含双鱼眼。与 App 图标同一套构图。 */
    private fun drawTaiji(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        if (r <= 0f) return

        // 纸色底盘
        fill(paperColor, alpha)
        canvas.drawCircle(cx, cy, r, paint)

        // 墨色右半边（-90° 起顺时针 180°，与 iOS 的 addArc(-90, 90, clockwise: false) 同向）
        halfPath.reset()
        halfPath.addArc(cx - r, cy - r, cx + r, cy + r, -90f, 180f)
        halfPath.close()
        canvas.drawPath(halfPath, paint)

        // 上鱼头为墨、下鱼头为纸 —— 两者各自跨过中线，合成 S 形
        fill(inkColor, alpha)
        canvas.drawCircle(cx, cy - r / 2f, r / 2f, paint)
        fill(paperColor, alpha)
        canvas.drawCircle(cx, cy + r / 2f, r / 2f, paint)

        // 双鱼眼：上眼为纸色、下眼为墨色
        fill(paperColor, alpha)
        canvas.drawCircle(cx, cy - r / 2f, r / 8f, paint)
        fill(inkColor, alpha)
        canvas.drawCircle(cx, cy + r / 2f, r / 8f, paint)

        // 最外一圈淡墨勾边
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = inkColor
        paint.alpha = (255 * alpha * 0.75f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r - dp(0.75f), paint)
    }

    private fun fill(color: Int, alpha: Float) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = color
        paint.alpha = (255f * alpha).toInt().coerceIn(0, 255)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
