package com.yijing.app.core

import com.nlf.calendar.Solar

/**
 * 农历与四柱：基于 cn.6tail:lunar 纯 Java 库（无 JNI）。
 * 公历 -> 农历年月日 + 八字（年/月/日/时四柱），用于梅花易数时间起卦。
 */
object LunarCalendar {

    data class TimeNumbers(
        val yearZhi: Int,   // 年支序 1..12（子=1）
        val month: Int,     // 农历月 1..12
        val day: Int,       // 农历日 1..30
        val hourZhi: Int,   // 时支序 1..12（子=1）
        val bazi: String    // 四柱 "年柱 月柱 日柱 时柱"
    )

    private val zhiOrder = mapOf(
        "子" to 1, "丑" to 2, "寅" to 3, "卯" to 4, "辰" to 5, "巳" to 6,
        "午" to 7, "未" to 8, "申" to 9, "酉" to 10, "戌" to 11, "亥" to 12
    )

    fun fromGregorian(year: Int, month: Int, day: Int, hour: Int, minute: Int): TimeNumbers? {
        return try {
            val solar = Solar.fromYmdHms(year, month, day, hour, minute, 0)
            val lunar = solar.getLunar()
            val ec = lunar.getEightChar()
            val yz = zhiOrder[ec.getYearZhi()] ?: return null
            val hz = zhiOrder[ec.getTimeZhi()] ?: return null
            val lm = lunar.getMonth()
            TimeNumbers(
                yearZhi = yz,
                month = if (lm < 0) -lm else lm,
                day = lunar.getDay(),
                hourZhi = hz,
                bazi = "${ec.getYear()} ${ec.getMonth()} ${ec.getDay()} ${ec.getTime()}"
            )
        } catch (e: Exception) {
            null
        }
    }
}