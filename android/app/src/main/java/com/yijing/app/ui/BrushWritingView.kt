package com.yijing.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * 毛笔运墨写字动画：逐笔写出「爻」字，写完收笔后循环。用于解卦等待页的占位，无需图片资源。
 *
 * 与 iOS 的 `BrushWritingView.swift` **逐参数对齐**，两端只在"纸、字、笔"三样东西上：
 * 1. 淡米字格：写字的底稿。
 * 2. 「爻」字四笔：已写完的整笔 + 当前笔只画到进度处。
 * 3. 毛笔：笔尖三角形 + 笔杆，跟着当前笔尖走；最后一笔写完即收笔隐去。
 *
 * 这里刻意不做"加法"——不加外环、不加光晕、不加淡入淡出。多一层装饰，两端的观感就会分叉，
 * 用户一眼就能看出 iOS 是一支笔在写、Android 变成了别的图形。比例也全部按控件高度取，
 * 与 iOS 的 `h` 同一套系数：字形半高 0.19h、格子 0.34h、墨线 0.045h。
 */
class BrushWritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 笔尖形状固定，建一次反复用，省得每帧新建 Path */
    private val tipPath = Path()

    private var phase = 0f

    /** 控件短边，等价于 iOS 里的 `h`，所有尺寸都按它取比例 */
    private var size = 0f

    private var animator: ValueAnimator? = null

    private val inkColor = Color.rgb(0x2B, 0x2A, 0x28)
    private val rodColor = Color.rgb(0xA8, 0x3A, 0x22)
    private val gridColor = Color.argb(22, 0x2B, 0x2A, 0x28)

    /** 「爻」字四笔（相对字形半高 s 的坐标，x 右为正、y 下为正），顺序：上撇/捺、下撇/捺 */
    private val strokes = arrayOf(
        floatArrayOf(0.34f, -0.82f, -0.34f, -0.18f),
        floatArrayOf(-0.34f, -0.82f, 0.34f, -0.18f),
        floatArrayOf(0.34f, 0.18f, -0.34f, 0.82f),
        floatArrayOf(-0.34f, 0.18f, 0.34f, 0.82f)
    )

    init {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                phase = a.animatedValue as Float
                invalidate()
            }
        }
    }

    fun start() {
        animator?.cancel()
        phase = 0f
        animator?.start()
        invalidate()
    }

    fun stop() {
        animator?.cancel()
    }

    /** 页面不可见时停掉，别在后台白烧 CPU */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (animator?.isStarted != true) start()
        } else {
            stop()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        size = min(w, h).toFloat()
        if (size <= 0f) return

        val bw = size * 0.030f
        val bl = size * 0.10f
        tipPath.reset()
        tipPath.moveTo(0f, 0f)
        tipPath.lineTo(-bw, -bl)
        tipPath.lineTo(bw, -bl)
        tipPath.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || size <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val s = size * 0.19f

        // 淡米字格
        val g = size * 0.34f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.012f
        paint.color = gridColor
        canvas.drawRect(cx - g, cy - g, cx + g, cy + g, paint)

        // 书写进度（末尾留 20% 停顿收笔）
        val perf = (phase / (1f - HOLD)).coerceIn(0f, 1f)
        val seg = perf * strokes.size
        val idx = seg.toInt()
        val t = (seg - idx).coerceIn(0f, 1f)

        // 墨迹
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = size * 0.045f
        paint.color = inkColor

        var brushX = 0f
        var brushY = 0f
        var hasBrush = false
        for (j in strokes.indices) {
            val st = strokes[j]
            val sx = cx + st[0] * s
            val sy = cy + st[1] * s
            val ex = cx + st[2] * s
            val ey = cy + st[3] * s
            when {
                j < idx -> canvas.drawLine(sx, sy, ex, ey, paint)
                j == idx -> {
                    val px = sx + (ex - sx) * t
                    val py = sy + (ey - sy) * t
                    canvas.drawLine(sx, sy, px, py, paint)
                    brushX = px
                    brushY = py
                    hasBrush = true
                }
                else -> break
            }
        }

        // 毛笔（写完最后一笔则收笔隐去）
        if (hasBrush && idx < strokes.size) drawBrush(canvas, brushX, brushY)
    }

    private fun drawBrush(canvas: Canvas, x: Float, y: Float) {
        val bl = size * 0.10f
        val bw = size * 0.030f
        val sl = size * 0.20f
        val rw = bw * 0.42f

        canvas.save()
        canvas.translate(x, y)

        paint.style = Paint.Style.FILL
        paint.color = inkColor
        canvas.drawPath(tipPath, paint)

        paint.color = rodColor
        canvas.drawRect(-rw, -bl - sl, rw, -bl, paint)

        canvas.restore()
    }

    private companion object {
        /** 一轮动画：0 ~ 0.8 写字，0.8 ~ 1 收笔停顿（与 iOS 的 duration = 2.6 / hold = 0.2 一致） */
        const val CYCLE_MS = 2600L
        const val HOLD = 0.2f
    }
}
