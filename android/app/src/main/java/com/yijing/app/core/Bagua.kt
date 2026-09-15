package com.yijing.app.core

/**
 * 八卦：先天卦序 + 三爻（自下而上，1=阳 / 0=阴）。
 */
enum class Trigram(
    val number: Int,
    val label: String,
    val lines: Int,
    val symbol: String,
    val direction: String,
    val nature: String
) {
    QIAN(1, "乾", 0b111, "☰", "西北", "天"),
    DUI(2, "兑", 0b110, "☱", "西", "泽"),
    LI(3, "离", 0b101, "☲", "南", "火"),
    ZHEN(4, "震", 0b100, "☳", "东", "雷"),
    XUN(5, "巽", 0b011, "☴", "东南", "风"),
    KAN(6, "坎", 0b010, "☵", "北", "水"),
    GEN(7, "艮", 0b001, "☶", "东北", "山"),
    KUN(8, "坤", 0b000, "☷", "西南", "地");

    companion object {
        fun fromNumber(n: Int): Trigram {
            val m = (((n - 1) % 8) + 8) % 8 + 1
            return entries.first { it.number == m }
        }

        fun fromLines(l: Int): Trigram = entries.first { it.lines == l }

        /** 后天八卦方位：方位名 -> 卦 */
        fun fromDirection(d: String): Trigram = entries.first { it.direction == d }
    }
}