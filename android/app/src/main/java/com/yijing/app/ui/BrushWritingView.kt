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

/**
 * 毛笔运墨写字动画：逐笔写出「爻」字，写完收笔后循环。
 * 用于解卦等待时的占位（书写 / 推演意象），自绘实现，无需图片资源。
 */
class BrushWritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private val inkColor = Color.rgb(0x2B, 0x2A, 0x28)
    private val rodColor = Color.rgb(0xA8, 0x3A, 0x22)
    private val gridColor = Color.argb(22, 0x2B, 0x2A, 0x28)

    // 「爻」字四笔（相对字形半高 s 的坐标，x 右为正、y 下为正），顺序：上赳撇/捺、下赳撇/捺
    private val strokes = arrayOf(
        floatArrayOf(0.34f, -0.82f, -0.34f, -0.18f),
        floatArrayOf(-0.34f, -0.82f, 0.34f, -0.18f),
        floatArrayOf(0.34f, 0.18f, -0.34f, 0.82f),
        floatArrayOf(-0.34f, 0.18f, 0.34f, 0.82f)
    )

    init {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600
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
        animator?.start()
    }

    fun stop() {
        animator?.cancel()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val s = h * 0.19f

        // 淡米字格
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.012f
        paint.color = gridColor
        val g = h * 0.34f
        canvas.drawRect(cx - g, cy - g, cx + g, cy + g, paint)
        canvas.drawLine(cx - g, cy - g, cx + g, cy - g, paint)

        // 书写进度（末尾留 20% 停顿收笔）
        val hold = 0.2f
        val perf = (phase / (1f - hold)).coerceIn(0f, 1f)
        val seg = perf * strokes.size
        val idx = seg.toInt()
        val t = (seg - idx).coerceIn(0f, 1f)

        // 墨迹（已写笔画 + 当前笔画进行中部分）
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * 0.045f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = inkColor
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
                }
            }
        }

        // 毛笔（写完最后一笔则收笔隐去）
        if (idx < strokes.size) {
            val st = strokes[idx]
            val sx = cx + st[0] * s
            val sy = cy + st[1] * s
            val ex = cx + st[2] * s
            val ey = cy + st[3] * s
            val px = sx + (ex - sx) * t
            val py = sy + (ey - sy) * t
            drawBrush(canvas, px, py, h)
        }
    }

    private fun drawBrush(canvas: Canvas, x: Float, y: Float, h: Float) {
        val bl = h * 0.10f
        val bw = h * 0.030f
        val sl = h * 0.20f
        val rw = bw * 0.42f

        canvas.save()
        canvas.translate(x, y)

        val tip = Path().apply {
            moveTo(0f, 0f)
            lineTo(-bw, -bl)
            lineTo(bw, -bl)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = inkColor
        canvas.drawPath(tip, paint)

        paint.color = rodColor
        canvas.drawRect(-rw, -bl - sl, rw, -bl, paint)

        canvas.restore()
    }
}