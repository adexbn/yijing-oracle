package com.yijing.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 圆形印章主按钮：红字白底、朱红双圈边框，内置篆书字体渲染正文。
 */
class SealButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var sealText: String = "起卦"
    var sealSubText: String = "今時空"

    private val cinnabar = Color.parseColor("#A63A2B")

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cinnabar
        style = Paint.Style.STROKE
    }
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cinnabar
        style = Paint.Style.STROKE
    }
    private val pressOverlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(14, 166, 58, 43)
        style = Paint.Style.FILL
    }

    private var sealTypeface: Typeface? = null

    init {
        sealTypeface = try {
            Typeface.createFromAsset(context.assets, "fonts/seal.ttf")
        } catch (e: Exception) {
            null
        }
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = min(w, h)
        setMeasuredDimension(side, side)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f - dp(3f)
        val scale = if (isPressed) 0.96f else 1f
        val r = base * scale

        // 白底
        canvas.drawCircle(cx, cy, r, fillPaint)

        // 外圈（朱红粗边）
        ringPaint.strokeWidth = dp(3f)
        canvas.drawCircle(cx, cy, r - dp(2f), ringPaint)

        // 内圈（细边）
        innerRingPaint.strokeWidth = dp(1.2f)
        canvas.drawCircle(cx, cy, r - dp(9f), innerRingPaint)

        // 正文「起卦」横排
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cinnabar
            textAlign = Paint.Align.CENTER
            textSize = r * 0.62f
            sealTypeface?.let { typeface = it }
        }
        drawCenteredText(canvas, sealText, cx, cy - r * 0.10f, textPaint)

        // 副字「今·時·空」
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cinnabar
            textAlign = Paint.Align.CENTER
            textSize = r * 0.20f
            sealTypeface?.let { typeface = it }
        }
        drawCenteredText(canvas, sealSubText, cx, cy + r * 0.42f, subPaint)

        if (isPressed) {
            canvas.drawCircle(cx, cy, r, pressOverlay)
        }
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float, p: Paint) {
        val fm = p.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2
        canvas.drawText(text, cx, baseline, p)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}