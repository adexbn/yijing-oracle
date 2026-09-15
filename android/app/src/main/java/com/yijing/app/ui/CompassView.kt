package com.yijing.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.yijing.app.core.Trigram
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 结果罗盘：外圈后天八卦（符号 + 卦名），中圈二十四山（天干 + 地支 + 四隅卦），
 * 中心显示本卦六爻（动爻高亮）与卦名、变卦，指针指向本卦上卦方位。
 */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var centerHex = "乾"
    private var changedHex = "乾"
    private var upperLines = 0b111
    private var lowerLines = 0b111
    private var movingLine = 1

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#201D17") }
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#837A69") }
    private val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A6D3B") }
    private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#C8A45C") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#8A6D3B")
    }
    private val faintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E4DED1")
    }

    // 后天八卦顺时针排布（自正北「坎」起）
    private val ringTrigrams = listOf(
        Trigram.KAN, Trigram.GEN, Trigram.ZHEN, Trigram.XUN,
        Trigram.LI, Trigram.KUN, Trigram.DUI, Trigram.QIAN
    )

    // 二十四山：自正北「子」起顺时针每 15°
    private val shan24 = listOf(
        "子", "癸", "丑", "艮", "寅", "甲", "卯", "乙", "辰", "巽", "巳", "丙",
        "午", "丁", "未", "坤", "申", "庚", "酉", "辛", "戌", "乾", "亥", "壬"
    )

    private val dirAngle = mapOf(
        "北" to 0, "东北" to 45, "东" to 90, "东南" to 135,
        "南" to 180, "西南" to 225, "西" to 270, "西北" to 315
    )

    fun bind(
        centerHex: String,
        changedHex: String,
        upperLines: Int,
        lowerLines: Int,
        movingLine: Int
    ) {
        this.centerHex = centerHex
        this.changedHex = changedHex
        this.upperLines = upperLines
        this.lowerLines = lowerLines
        this.movingLine = movingLine
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = min(w, h)
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - dp(12f)

        drawRing(canvas, cx, cy, r)
        drawShan24(canvas, cx, cy, r)
        drawBaguaRing(canvas, cx, cy, r)
        drawCenter(canvas, cx, cy, r)
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        faintRing.strokeWidth = dp(1f)
        ring.strokeWidth = dp(1.5f)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, r * 0.66f, faintRing)
        canvas.drawCircle(cx, cy, r * 0.52f, faintRing)
        canvas.drawCircle(cx, cy, r * 0.44f, ring)
        // 上/下（阳/阴）半分界
        canvas.drawLine(cx - r, cy, cx + r, cy, faintRing)
    }

    private fun drawShan24(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val rr = r * 0.56f
        muted.textSize = dp(14f)
        for (i in 0 until 24) {
            val a = Math.toRadians(-90.0 + i * 15.0)
            val tx = cx + rr * cos(a).toFloat()
            val ty = cy + rr * sin(a).toFloat()
            drawCentered(canvas, shan24[i], tx, ty, muted)
        }
    }

    private fun drawBaguaRing(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val upper = Trigram.fromLines(upperLines)
        val lower = Trigram.fromLines(lowerLines)
        for (i in 0 until 8) {
            val tg = ringTrigrams[i]
            val hot = tg == upper || tg == lower
            val a = Math.toRadians(-90.0 + i * 45.0)
            // 符号（外圈）
            val sx = cx + r * 0.88f * cos(a).toFloat()
            val sy = cy + r * 0.88f * sin(a).toFloat()
            val symPaint = if (hot) gold else ink
            symPaint.textSize = dp(18f)
            drawCentered(canvas, tg.symbol, sx, sy, symPaint)
            // 卦名（内圈）
            val nx = cx + r * 0.72f * cos(a).toFloat()
            val ny = cy + r * 0.72f * sin(a).toFloat()
            val namePaint = if (hot) brand else muted
            namePaint.textSize = dp(14f)
            drawCentered(canvas, tg.label, nx, ny, namePaint)
        }
    }

    private fun drawCenter(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val rc = r * 0.44f
        // 本卦六爻（下卦在下，上卦在上），动爻金色标记
        val lineW = rc * 0.92f
        val gap = dp(7f)
        val startY = cy - gap * 2.5f
        ink.strokeWidth = dp(2.5f)
        gold.strokeWidth = dp(2.5f)
        for (i in 0 until 6) {
            val yang = if (i < 3) ((lowerLines shr i) and 1) == 1 else ((upperLines shr (i - 3)) and 1) == 1
            val p = if (i + 1 == movingLine) gold else ink
            val y = startY + i * gap
            drawYao(canvas, cx, y, lineW, yang, p)
        }
        // 卦名与变卦已上移至顶部标题区，中心仅保留六爻卦象，避免与符号重叠。
        // 指针指向本卦上卦方位
        val upper = Trigram.fromLines(upperLines)
        val deg = dirAngle[upper.direction] ?: 0
        drawNeedle(canvas, cx, cy, r, deg)
    }

    private fun drawNeedle(canvas: Canvas, cx: Float, cy: Float, r: Float, deg: Int) {
        // 外圈小三角游标：贴在外圈符号环外侧，尖端指向本卦上卦方位，不与环内文字重叠。
        val a = Math.toRadians(-90.0 + deg)
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        // 三角尖端（朝外，落在符号环与卦名环之间）
        val tipR = r * 0.90f
        val tipX = cx + tipR * dx
        val tipY = cy + tipR * dy
        // 底边两个顶点（位于外圈边缘，朝圆心方向偏移）
        val baseR = r * 0.98f
        val half = dp(5f)
        val px = -dy
        val py = dx
        val b1x = cx + baseR * dx + px * half
        val b1y = cy + baseR * dy + py * half
        val b2x = cx + baseR * dx - px * half
        val b2y = cy + baseR * dy - py * half

        gold.style = Paint.Style.FILL
        val path = android.graphics.Path().apply {
            moveTo(tipX, tipY)
            lineTo(b1x, b1y)
            lineTo(b2x, b2y)
            close()
        }
        canvas.drawPath(path, gold)
    }

    private fun drawYao(canvas: Canvas, cx: Float, y: Float, w: Float, yang: Boolean, p: Paint) {
        if (yang) {
            canvas.drawLine(cx - w / 2, y, cx + w / 2, y, p)
        } else {
            val g = w * 0.18f
            canvas.drawLine(cx - w / 2, y, cx - g, y, p)
            canvas.drawLine(cx + g, y, cx + w / 2, y, p)
        }
    }

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float, p: Paint) {
        p.textAlign = Paint.Align.CENTER
        val fm = p.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2
        canvas.drawText(text, cx, baseline, p)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}