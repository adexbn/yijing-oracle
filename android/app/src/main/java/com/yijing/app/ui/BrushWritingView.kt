package com.yijing.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.yijing.app.core.Trigram
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 毛笔运墨写字动画：逐笔写出「爻」字，写完停笔、墨迹淡去，再从起笔处重来。
 *
 * 画面自外向内分四层，让它看起来像一场「推演」而不是一个静态图标：
 * 1. 八卦环：后天八卦自正北「坎」起顺时针排布，整环每轮缓慢转过 45°（正好走一格，
 *    循环处看不出接头）；一枚金色高亮顺着卦位走，走到下一卦时用透明度过渡，不跳。
 * 2. 气晕：中心一团极淡的金色，随书写节奏起伏一次，收笔时最浓。
 * 3. 米字格：写字的底稿。
 * 4. 墨迹与毛笔：先铺一层更宽更淡的墨（生宣洇开），再压实笔锋本身。
 *
 * 全部为自绘，无图片资源，尺寸按控件短边取比例，换机型不会走形。
 */
class BrushWritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 笔杆笔尖的形状是固定的，建一次反复用，省得每帧新建 Path */
    private val tipPath = Path()

    private var phase = 0f
    private var size = 0f                       // 控件短边，所有尺寸都按它取比例
    private var glowShader: RadialGradient? = null
    private var animator: ValueAnimator? = null

    private val inkColor = Color.rgb(0x2B, 0x2A, 0x28)
    private val rodColor = Color.rgb(0xA8, 0x3A, 0x22)
    private val gridColor = Color.argb(24, 0x2B, 0x2A, 0x28)
    private val ringColor = Color.argb(54, 0x8A, 0x6D, 0x3B)     // brand
    private val paperColor = Color.rgb(0xFB, 0xF8, 0xF2)         // 与等待页底色一致
    private val symbolColor = Color.argb(112, 0x20, 0x1D, 0x17)  // ink
    private val symbolHot = Color.argb(215, 0xC8, 0xA4, 0x5C)    // gold

    // 后天八卦顺时针排布（自正北「坎」起），与结果页罗盘同一顺序、同一走法
    private val ringTrigrams = listOf(
        Trigram.KAN, Trigram.GEN, Trigram.ZHEN, Trigram.XUN,
        Trigram.LI, Trigram.KUN, Trigram.DUI, Trigram.QIAN
    )

    // 「爻」字四笔（相对字形半高 s 的坐标，x 右为正、y 下为正），顺序：上赳撇/捺、下赳撇/捺
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

        // 气晕用固定的渐变，每帧只调透明度，避免每帧新建 shader
        glowShader = RadialGradient(
            w / 2f, h / 2f, size * 0.42f,
            Color.argb(34, 0xC8, 0xA4, 0x5C), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )

        val bw = size * 0.028f
        val bl = size * 0.075f
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

        // 起笔淡入、收笔淡出，循环处就不会有硬切
        val fadeIn = (phase / 0.06f).coerceIn(0f, 1f)
        val fadeOut = if (phase <= HOLD_END) 1f
        else (1f - (phase - HOLD_END) / (1f - HOLD_END)).coerceIn(0f, 1f)
        val inkAlpha = fadeIn * fadeOut

        drawGlow(canvas, cx, cy)
        drawGrid(canvas, cx, cy)
        drawBaguaRing(canvas, cx, cy)

        // 书写进度（写字占前 72%，之后停笔一拍再淡出）
        val perf = (phase / WRITE_END).coerceIn(0f, 1f)
        val seg = perf * strokes.size
        val idx = seg.toInt()
        val t = (seg - idx).coerceIn(0f, 1f)
        if (perf <= 0f) return

        val s = size * 0.17f
        drawInk(canvas, cx, cy, s, idx, t, inkAlpha)

        // 毛笔（写完最后一笔就收笔隐去）
        if (idx < strokes.size) {
            val st = strokes[idx]
            val sx = cx + st[0] * s
            val sy = cy + st[1] * s
            val ex = cx + st[2] * s
            val ey = cy + st[3] * s
            drawBrush(canvas, sx + (ex - sx) * t, sy + (ey - sy) * t, inkAlpha)
        }
    }

    /** 气晕：一轮呼吸一次，起笔时最淡、收笔时最浓，首尾相接 */
    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float) {
        val shader = glowShader ?: return
        val breath = (1f - cos(phase * TWO_PI)) / 2f
        glowPaint.shader = shader
        glowPaint.alpha = (16 + 18 * breath).toInt()
        canvas.drawCircle(cx, cy, size * 0.42f, glowPaint)
        glowPaint.shader = null
    }

    /** 淡米字格（仅外框，线色由 gridColor 自带透明度） */
    private fun drawGrid(canvas: Canvas, cx: Float, cy: Float) {
        val g = size * 0.27f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.009f
        paint.color = gridColor
        canvas.drawRect(cx - g, cy - g, cx + g, cy + g, paint)
    }

    /** 八卦外环：整环每轮转 45°，金色高亮顺着卦位走一圈 */
    private fun drawBaguaRing(canvas: Canvas, cx: Float, cy: Float) {
        val r = size * 0.465f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.007f
        paint.color = ringColor
        canvas.drawCircle(cx, cy, r, paint)

        val spin = phase * 45f
        val lead = phase * 8f
        val hi = lead.toInt() % 8
        val hiNext = (hi + 1) % 8
        val frac = lead - lead.toInt()

        textPaint.textSize = size * 0.075f
        for (i in 0 until 8) {
            val a = Math.toRadians(-90.0 + i * 45.0 + spin)
            val tx = cx + r * cos(a).toFloat()
            val ty = cy + r * sin(a).toFloat()

            // 先用纸色压掉环线，符号才不会被圆穿过
            paint.style = Paint.Style.FILL
            paint.color = paperColor
            canvas.drawCircle(tx, ty, size * 0.052f, paint)

            textPaint.color = symbolColor
            drawCentered(canvas, ringTrigrams[i].symbol, tx, ty, textPaint)

            // 金色高亮：在相邻两卦之间用透明度过渡，看起来是走过去的
            val highlight = when (i) {
                hi -> ((1f - frac) * 210f).toInt()
                hiNext -> (frac * 210f).toInt()
                else -> 0
            }
            if (highlight > 0) {
                textPaint.color = symbolHot
                textPaint.alpha = highlight
                drawCentered(canvas, ringTrigrams[i].symbol, tx, ty, textPaint)
                textPaint.alpha = 255
            }
        }
    }

    /** 墨迹：先宽而淡地洇一层，再压实笔画本身 */
    private fun drawInk(
        canvas: Canvas, cx: Float, cy: Float, s: Float, idx: Int, t: Float, alpha: Float
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = inkColor

        paint.strokeWidth = size * 0.095f
        paint.alpha = (34 * alpha).toInt()
        drawStrokes(canvas, cx, cy, s, idx, t)

        paint.strokeWidth = size * 0.045f
        paint.alpha = (255 * alpha).toInt()
        drawStrokes(canvas, cx, cy, s, idx, t)
    }

    /** 逐笔画线：已写完的整笔，当前笔只画到进度 t；idx 越界（写完）时全部整笔画 */
    private fun drawStrokes(canvas: Canvas, cx: Float, cy: Float, s: Float, idx: Int, t: Float) {
        for (j in strokes.indices) {
            if (j > idx) break
            val st = strokes[j]
            val sx = cx + st[0] * s
            val sy = cy + st[1] * s
            val ex = cx + st[2] * s
            val ey = cy + st[3] * s
            val px = if (j == idx) sx + (ex - sx) * t else ex
            val py = if (j == idx) sy + (ey - sy) * t else ey
            canvas.drawLine(sx, sy, px, py, paint)
        }
    }

    private fun drawBrush(canvas: Canvas, x: Float, y: Float, alpha: Float) {
        val bl = size * 0.075f
        val rw = size * 0.028f * 0.42f

        canvas.save()
        canvas.translate(x, y)

        paint.style = Paint.Style.FILL
        paint.color = inkColor
        paint.alpha = (255 * alpha).toInt()
        canvas.drawPath(tipPath, paint)

        // 换色会把 alpha 重置成 255，起笔淡入时笔杆也要跟着淡，得再压一次
        paint.color = rodColor
        paint.alpha = (255 * alpha).toInt()
        canvas.drawRect(-rw, -bl - size * 0.15f, rw, -bl, paint)

        canvas.restore()
    }

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float, p: Paint) {
        val fm = p.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2
        canvas.drawText(text, cx, baseline, p)
    }

    private companion object {
        /** 一轮动画：0 ~ 0.72 写字，0.72 ~ 0.84 停笔，0.84 ~ 1 墨迹淡去 */
        const val CYCLE_MS = 3200L
        const val WRITE_END = 0.72f
        const val HOLD_END = 0.84f
        const val TWO_PI = 6.2831855f
    }
}
