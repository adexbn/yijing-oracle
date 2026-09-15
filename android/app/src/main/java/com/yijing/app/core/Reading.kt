package com.yijing.app.core

/**
 * 通用解卦：无提问时，按 事业 / 感情 / 健康 / 抉择 四维输出解读。
 * 基于卦辞意象、动爻位置、体用生克及变卦走向综合推导。
 */
data class GenericReading(
    val career: String,
    val love: String,
    val health: String,
    val decision: String
)

object Reading {

    // ---------- 五行 ----------

    private fun wuxing(t: Trigram): String = when (t) {
        Trigram.QIAN, Trigram.DUI -> "金"
        Trigram.ZHEN, Trigram.XUN -> "木"
        Trigram.KAN -> "水"
        Trigram.LI -> "火"
        Trigram.KUN, Trigram.GEN -> "土"
    }

    private fun bodyPart(t: Trigram): String = when (t) {
        Trigram.QIAN -> "头/肺"
        Trigram.KUN -> "腹/脾"
        Trigram.ZHEN -> "足/肝"
        Trigram.XUN -> "股/胆"
        Trigram.KAN -> "耳/肾"
        Trigram.LI -> "目/心"
        Trigram.GEN -> "手/胃"
        Trigram.DUI -> "口/肺"
    }

    private val wuXingOrder = listOf("金", "水", "木", "火", "土")

    /** 体用关系：下卦为体（己），上卦为用（彼/环境）。 */
    private fun tiYong(lower: Trigram, upper: Trigram): String {
        val ti = wuxing(lower)
        val yong = wuxing(upper)
        if (ti == yong) return "比和"
        val tiIdx = wuXingOrder.indexOf(ti)
        val yongIdx = wuXingOrder.indexOf(yong)
        if ((yongIdx + 1) % 5 == tiIdx) return "用生体"
        if ((tiIdx + 1) % 5 == yongIdx) return "体生用"
        if ((yongIdx + 2) % 5 == tiIdx) return "用克体"
        return "体克用"
    }

    /** 卦辞简易吉凶判断。 */
    private fun isJi(hex: Hexagram): Boolean {
        val j = hex.judgment
        return j.contains("吉") || j.contains("亨") || j.contains("利") ||
                j.startsWith("元") || j.contains("无咎")
    }

    private fun isXiong(hex: Hexagram): Boolean {
        val j = hex.judgment
        return j.contains("凶") || j.contains("咎") || j.contains("悔") ||
                j.contains("吝") || j.contains("不利")
    }

    // ---------- 通用解读 ----------

    fun genericReading(r: DivinationResult): GenericReading {
        val orig = r.original
        val chg = r.changed
        val ty = tiYong(r.lower, r.upper)

        return GenericReading(
            career = careerReading(orig, chg, r.movingLine, ty),
            love = loveReading(orig, chg, r.lower, r.upper, ty),
            health = healthReading(r.lower, r.upper, r.movingLine, ty),
            decision = decisionReading(orig, chg, r.movingLine, ty)
        )
    }

    private fun careerReading(orig: Hexagram, chg: Hexagram, ml: Int, ty: String): String {
        val base = "${orig.name}卦主${careerKeyword(orig)}。"
        val move = if (ml > 3)
            "动爻在上卦，气机向上，宜主动求变、争取机会。"
        else
            "动爻在下卦，气机未浮，宜深耕积累、静待时机。"
        val tyAdvice = when (ty) {
            "用生体" -> "外部环境对你有利，贵人运佳，顺势而为。"
            "体生用" -> "你付出较多，劳而有获，但需注意节奏。"
            "用克体" -> "外部压力较大，不宜硬碰，迂回为上。"
            "体克用" -> "你有掌控力，宜主动出击，成事在己。"
            else -> "内外平衡，按部就班即可。"
        }
        val chgNote = if (isJi(chg) && !isXiong(chg))
            "变卦${chg.name}卦向好，坚持可成。"
        else if (isXiong(chg) && !isJi(chg))
            "变卦${chg.name}卦有阻，短期不宜冒进。"
        else ""
        return "$base$move$tyAdvice$chgNote"
    }

    private fun loveReading(orig: Hexagram, chg: Hexagram, lower: Trigram, upper: Trigram, ty: String): String {
        val base = "${orig.name}卦在感情上${loveKeyword(orig)}。"
        val tiName = lower.label
        val yongName = upper.label
        val tyDesc = when (ty) {
            "用生体" -> "对方（${yongName}卦象）对你用心，感情滋养。"
            "体生用" -> "你（${tiName}卦象）付出更多，乐在其中但有倦时。"
            "用克体" -> "对方或环境让你感到压力，需坦诚沟通。"
            "体克用" -> "你处于主导地位，注意别让对方感到被压制。"
            else -> "彼此势均力敌，相敬如宾。"
        }
        val chgNote = if (isJi(chg) && !isXiong(chg))
            "变卦${chg.name}卦吉，关系向好发展。"
        else if (isXiong(chg) && !isJi(chg))
            "变卦${chg.name}卦不利，需多些耐心磨合。"
        else ""
        return "$base$tyDesc$chgNote"
    }

    private fun healthReading(lower: Trigram, upper: Trigram, ml: Int, ty: String): String {
        val bp = bodyPart(if (ml > 3) upper else lower)
        val area = if (ml > 3) "上半身" else "下半身"
        val base = "${wuxing(lower)}性体质。"
        val focus = "动爻在${if (ml > 3) "上" else "下"}卦，近期留意$area（${bp}）。"
        val tyNote = when (ty) {
            "用克体" -> "外部环境对健康不利，注意劳逸结合。"
            "体克用" -> "体质尚可，但消耗较大，宜补养。"
            "体生用" -> "容易透支，注意睡眠和饮食规律。"
            "用生体" -> "身心状态良好，保持即可。"
            else -> "机能平稳，适度运动有益。"
        }
        return "$base$focus$tyNote"
    }

    private fun decisionReading(orig: Hexagram, chg: Hexagram, ml: Int, ty: String): String {
        val move = if (ml > 3) "动爻在上卦，宜动不宜静——若在两难之间，选更难的那条路，安逸反生悔。"
        else "动爻在下卦，宜守不宜攻——若在两难之间，选那个已经坚持更久的方向，时机未到勿轻弃。"

        val origJi = isJi(orig) && !isXiong(orig)
        val chgJi = isJi(chg) && !isXiong(chg)
        val trend = when {
            origJi && !chgJi -> "本卦吉而变卦不吉，见好就收，不宜贪多。"
            !origJi && chgJi -> "本卦不吉而变卦向吉，再坚持一下，转折不远。"
            origJi && chgJi -> "本卦变卦皆吉，进退皆可，随心意而行。"
            !origJi && !chgJi -> "本卦变卦皆不吉，此时不宜做重大决定，宜静观其变。"
            else -> "卦象中和，谨慎权衡即可。"
        }

        val tyAdvice = when (ty) {
            "体克用", "用克体" -> "宜独立决策，少听他人干扰。"
            "用生体", "体生用" -> "宜与人合作，借力而行。"
            else -> "可独行亦可结伴，看具体情况。"
        }

        return "$move$trend$tyAdvice"
    }

    private fun careerKeyword(hex: Hexagram): String = when (hex.number) {
        1 -> "开创进取"
        2 -> "厚德载物"
        3 -> "万事开头难"
        4 -> "启蒙待发"
        5 -> "耐心等待"
        6 -> "避免争执"
        7 -> "统率有方"
        8 -> "亲附团结"
        9 -> "小有积蓄"
        10 -> "谨言慎行"
        11 -> "通泰顺遂"
        12 -> "闭塞不通"
        13 -> "与人合作"
        14 -> "丰盛收获"
        15 -> "谦虚受益"
        16 -> "顺势而动"
        17 -> "随缘而行"
        18 -> "整顿旧弊"
        19 -> "亲临督导"
        20 -> "观察待机"
        21 -> "果断裁决"
        22 -> "文饰包装"
        23 -> "剥落衰退"
        24 -> "一阳来复"
        25 -> "顺其自然"
        26 -> "厚积薄发"
        27 -> "自食其力"
        28 -> "过犹不及"
        29 -> "险中求进"
        30 -> "依附光明"
        31 -> "感而遂通"
        32 -> "持之以恒"
        33 -> "退避保全"
        34 -> "强盛勿骄"
        35 -> "蒸蒸日上"
        36 -> "韬光养晦"
        37 -> "齐家为先"
        38 -> "求同存异"
        39 -> "知难而退"
        40 -> "解脱困境"
        41 -> "损己利人"
        42 -> "增益其德"
        43 -> "决断去留"
        44 -> "不期而遇"
        45 -> "聚集人才"
        46 -> "步步高升"
        47 -> "困而知变"
        48 -> "固本培元"
        49 -> "变革图新"
        50 -> "革故鼎新"
        51 -> "临危不乱"
        52 -> "知止不殆"
        53 -> "循序渐进"
        54 -> "名分不正"
        55 -> "盛极而衰"
        56 -> "客居他乡"
        57 -> "柔顺服从"
        58 -> "和悦相处"
        59 -> "涣散重整"
        60 -> "节制有度"
        61 -> "诚信为本"
        62 -> "小处着手"
        63 -> "功成防变"
        64 -> "尚未完成"
        else -> "顺势而为"
    }

    private fun loveKeyword(hex: Hexagram): String = when (hex.number) {
        31 -> "感应相吸，自然生情"
        32 -> "长久之道，恒心为贵"
        44 -> "不期而遇的缘分"
        54 -> "需注意名分与位置"
        37 -> "家和万事兴"
        53 -> "细水长流，不可急躁"
        11 -> "天地交泰，感情和顺"
        12 -> "沟通不畅，需破冰"
        58 -> "愉悦相处，以和为贵"
        17 -> "随缘就势，莫强求"
        21 -> "有阻碍需化解"
        38 -> "观念不同但可调和"
        41 -> "有舍才有得"
        42 -> "彼此增益，共同成长"
        else -> "看卦象而定"
    }
}