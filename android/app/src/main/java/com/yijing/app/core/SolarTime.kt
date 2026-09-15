package com.yijing.app.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 真太阳时：平太阳时 + 经度订正 + 时差（equation of time）。
 * 中国标准时以东经 120° 为基准。
 */
object SolarTime {

    const val STANDARD_MERIDIAN = 120.0

    /** 时差方程，返回分钟。dayOfYear 取 1..366。 */
    fun equationOfTime(dayOfYear: Int): Double {
        val b = 2.0 * PI * (dayOfYear - 81) / 364.0
        return 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
    }

    /**
     * 由钟表时刻（分钟，当日 0:00 起）换算真太阳时（分钟）。
     * @param stdClockMinutes 标准钟表时刻，0..1439
     * @param longitudeEast   东经度数（如 121.47）
     */
    fun trueSolarMinutes(stdClockMinutes: Double, longitudeEast: Double, dayOfYear: Int): Double {
        val longitudeCorrection = 4.0 * (longitudeEast - STANDARD_MERIDIAN)
        return stdClockMinutes + longitudeCorrection + equationOfTime(dayOfYear)
    }

    /** 时辰（子丑寅卯…）：真太阳时每 2 小时为一个时辰，0=子时，11=亥时。 */
    fun shichen(trueSolarMinutes: Double): Int {
        val normalized = ((trueSolarMinutes % 1440) + 1440) % 1440
        return ((normalized / 120.0).toInt()) % 12
    }

    val SHICHEN_NAMES = arrayOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
}