package com.yijing.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.yijing.app.R
import com.yijing.app.core.Trigram

/**
 * 六爻竖排展示：上爻（第6爻）到初爻（第1爻）自上而下排列，
 * 阳爻为实线、阴爻为断线，并用朱红底+「动爻」徽标高亮动爻。紧凑排版。
 */
class HexagramLinesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var upper: Trigram = Trigram.QIAN
    private var lower: Trigram = Trigram.QIAN
    private var movingLine: Int = 0

    private val ink = ContextCompat.getColor(context, R.color.ink)
    private val inkMuted = ContextCompat.getColor(context, R.color.ink_muted)
    private val cinnabar = ContextCompat.getColor(context, R.color.cinnabar)
    private val paper = ContextCompat.getColor(context, R.color.paper)
    private val cinnabarLight = (0x1A shl 24) or (cinnabar and 0x00FFFFFF)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        strokeCap = Paint.Cap.ROUND
    }
    private val movingLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cinnabar
        strokeCap = Paint.Cap.ROUND
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cinnabarLight }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cinnabar }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkMuted
        textSize = dp(11f)
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paper
        textSize = dp(9f)
        textAlign = Paint.Align.CENTER
    }

    fun bind(upper: Trigram, lower: Trigram, movingLine: Int) {
        this.upper = upper
        this.lower = lower
        this.movingLine = movingLine
        invalidate()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun rowH(): Float = dp(22f)
    private fun gap(): Float = dp(3f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (rowH() * 6 + gap() * 5).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val labelW = dp(42f)
        val top = paddingTop.toFloat()
        val lineStroke = dp(3f)

        for (i in 6 downTo 1) {
            val yang = isYang(i)
            val idx = 6 - i
            val rowTop = top + idx * (rowH() + gap())
            val rowBottom = rowTop + rowH()
            val cy = (rowTop + rowBottom) / 2f

            if (i == movingLine) {
                canvas.drawRoundRect(
                    RectF(0f, rowTop, w, rowBottom),
                    dp(9f), dp(9f), bgPaint
                )
            }

            val label = labelOf(i, yang)
            val baseline = cy - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(label, labelW - labelPaint.measureText(label) - dp(5f), baseline, labelPaint)

            val paint = if (i == movingLine) movingLinePaint else linePaint
            paint.strokeWidth = lineStroke
            val barStart = labelW
            val barEnd = w - dp(48f)
            if (yang) {
                canvas.drawLine(barStart, cy, barEnd, cy, paint)
            } else {
                val gapPx = dp(6f)
                val mid = (barStart + barEnd) / 2f
                canvas.drawLine(barStart, cy, mid - gapPx, cy, paint)
                canvas.drawLine(mid + gapPx, cy, barEnd, cy, paint)
            }

            if (i == movingLine) {
                val bw = dp(30f)
                val bh = dp(16f)
                val bx = w - bw - dp(6f)
                val by = cy - bh / 2f
                canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), bh / 2f, bh / 2f, badgePaint)
                val bcy = by + bh / 2f - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
                canvas.drawText("动爻", bx + bw / 2f, bcy, badgeTextPaint)
            }
        }
    }

    private fun isYang(line: Int): Boolean {
        val lines = if (line <= 3) lower.lines else upper.lines
        val bit = if (line <= 3) line - 1 else line - 4
        return (lines and (1 shl bit)) != 0
    }

    private fun labelOf(line: Int, yang: Boolean): String {
        val y = if (yang) "九" else "六"
        val pos = when (line) {
            1 -> "初"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; else -> "上"
        }
        return if (line == 1 || line == 6) pos + y else y + pos
    }
}