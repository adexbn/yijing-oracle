package com.yijing.app.core

/**
 * 起卦结果：本卦 + 变卦 + 动爻。
 */
data class DivinationResult(
    val method: String,
    val lower: Trigram,
    val upper: Trigram,
    val movingLine: Int,
    val original: Hexagram,
    val changed: Hexagram
) : java.io.Serializable

object Divination {

    fun build(method: String, lower: Trigram, upper: Trigram, movingLine: Int): DivinationResult {
        val m = if (movingLine <= 0) 6 else ((movingLine - 1) % 6) + 1
        val original = HexagramDb.get(lower, upper)
        val changed = flip(original, m)
        return DivinationResult(method, lower, upper, m, original, changed)
    }

    /** 翻动某爻（自下而上 1..6）得到变卦。 */
    private fun flip(h: Hexagram, line: Int): Hexagram {
        val mask = 1 shl (line - 1)
        val lower = if (line <= 3) {
            Trigram.fromLines(h.lower.lines xor mask)
        } else h.lower
        val upper = if (line > 3) {
            Trigram.fromLines(h.upper.lines xor (1 shl (line - 4)))
        } else h.upper
        return HexagramDb.get(lower, upper)
    }

    /**
     * 有提问时，以提问字数修正动爻（本卦不变，变卦随动爻而变）。
     * 梅花易数「以问起卦」：同一时空下不同问题得不同动爻，避免同卦异问。
     */
    fun adjustByQuestion(result: DivinationResult, question: String): DivinationResult {
        val len = question.trim().length
        if (len == 0) return result
        return build(result.method, result.lower, result.upper, result.movingLine + len)
    }

    /** 时间起卦（梅花易数）：年+月+日 -> 上卦；年+月+日+时 -> 下卦/动爻。 */
    fun fromDateTime(year: Int, month: Int, day: Int, hour: Int): DivinationResult {
        val sum1 = year + month + day
        val sum2 = sum1 + hour
        val upper = Trigram.fromNumber(sum1)
        val lower = Trigram.fromNumber(sum2)
        val moving = ((sum2 - 1) % 6) + 1
        return build("时间起卦", lower, upper, moving)
    }

    /** 报数起卦：三数 -> 上卦 / 下卦 / 动爻。 */
    fun fromNumbers(n1: Int, n2: Int, n3: Int): DivinationResult {
        val upper = Trigram.fromNumber(n1)
        val lower = Trigram.fromNumber(n2)
        val moving = ((n3 - 1) % 6) + 1
        return build("报数起卦", lower, upper, moving)
    }

    /** 方位起卦：所向方位 -> 上卦；时辰数 -> 下卦；方位数+时辰 -> 动爻。 */
    fun fromDirection(direction: String, shichenIndex: Int): DivinationResult {
        val upper = Trigram.fromDirection(direction)
        val hourNum = shichenIndex + 1
        val lower = Trigram.fromNumber(hourNum)
        val moving = (((upper.number + hourNum - 1) % 6) + 1)
        return build("方位起卦", lower, upper, moving)
    }

    /** 时间起卦（农历）：年支 + 月 + 日 -> 上卦；再 + 时支 -> 下卦与动爻。 */
    fun fromLunarNumbers(yearZhi: Int, month: Int, day: Int, hourZhi: Int): DivinationResult {
        val sum1 = yearZhi + month + day
        val sum2 = sum1 + hourZhi
        val upper = Trigram.fromNumber(sum1)
        val lower = Trigram.fromNumber(sum2)
        val moving = ((sum2 - 1) % 6) + 1
        return build("时间起卦（农历）", lower, upper, moving)
    }
}