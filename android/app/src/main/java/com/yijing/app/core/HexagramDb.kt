package com.yijing.app.core

data class Hexagram(
    val number: Int,
    val name: String,
    val lower: Trigram,
    val upper: Trigram,
    val judgment: String
) : java.io.Serializable {
    val symbol: String get() = upper.symbol + lower.symbol
    val brief: String get() = "第${number}卦 · $name $symbol"

    /** 别名（卦象名）：纯卦为「离为火」，重卦为「火风鼎」式。 */
    val alias: String
        get() = if (upper == lower) "${name}为${upper.nature}" else "${upper.nature}${lower.nature}${name}"

    /** 六十四卦 Unicode 符号（自 U+4DC0 起按卦序）。 */
    val unicodeSymbol: Char get() = (0x4DC0 + number - 1).toChar()
}

/**
 * 六十四卦离线库。由（下卦, 上卦）可查本卦；卦辞取通行本「卦辞」原文（不含爻辞）。
 */
object HexagramDb {

    private val all = listOf(
        // 下卦乾
        Hexagram(1, "乾", Trigram.QIAN, Trigram.QIAN, "元亨利贞。"),
        Hexagram(43, "夬", Trigram.QIAN, Trigram.DUI, "扬于王庭，孚号有厉。告自邑，不利即戎。利有攸往。"),
        Hexagram(14, "大有", Trigram.QIAN, Trigram.LI, "元亨。"),
        Hexagram(34, "大壮", Trigram.QIAN, Trigram.ZHEN, "利贞。"),
        Hexagram(9, "小畜", Trigram.QIAN, Trigram.XUN, "亨。密云不雨，自我西郊。"),
        Hexagram(5, "需", Trigram.QIAN, Trigram.KAN, "有孚，光亨，贞吉。利涉大川。"),
        Hexagram(26, "大畜", Trigram.QIAN, Trigram.GEN, "利贞。不家食吉。利涉大川。"),
        Hexagram(11, "泰", Trigram.QIAN, Trigram.KUN, "小往大来，吉亨。"),
        // 下卦兑
        Hexagram(10, "履", Trigram.DUI, Trigram.QIAN, "履虎尾，不咥人，亨。"),
        Hexagram(58, "兑", Trigram.DUI, Trigram.DUI, "亨，利贞。"),
        Hexagram(38, "睽", Trigram.DUI, Trigram.LI, "小事吉。"),
        Hexagram(54, "归妹", Trigram.DUI, Trigram.ZHEN, "征凶，无攸利。"),
        Hexagram(61, "中孚", Trigram.DUI, Trigram.XUN, "豚鱼吉。利涉大川，利贞。"),
        Hexagram(60, "节", Trigram.DUI, Trigram.KAN, "亨。苦节不可贞。"),
        Hexagram(41, "损", Trigram.DUI, Trigram.GEN, "有孚，元吉，无咎，可贞。利有攸往。曷之用？二簋可用享。"),
        Hexagram(19, "临", Trigram.DUI, Trigram.KUN, "元亨利贞。至于八月有凶。"),
        // 下卦离
        Hexagram(13, "同人", Trigram.LI, Trigram.QIAN, "同人于野，亨。利涉大川，利君子贞。"),
        Hexagram(49, "革", Trigram.LI, Trigram.DUI, "己日乃孚。元亨利贞，悔亡。"),
        Hexagram(30, "离", Trigram.LI, Trigram.LI, "利贞，亨。畜牝牛吉。"),
        Hexagram(55, "丰", Trigram.LI, Trigram.ZHEN, "亨。王假之，勿忧，宜日中。"),
        Hexagram(37, "家人", Trigram.LI, Trigram.XUN, "利女贞。"),
        Hexagram(63, "既济", Trigram.LI, Trigram.KAN, "亨小，利贞。初吉终乱。"),
        Hexagram(22, "贲", Trigram.LI, Trigram.GEN, "亨。小利有攸往。"),
        Hexagram(36, "明夷", Trigram.LI, Trigram.KUN, "利艰贞。"),
        // 下卦震
        Hexagram(25, "无妄", Trigram.ZHEN, Trigram.QIAN, "元亨利贞。其匪正有眚，不利有攸往。"),
        Hexagram(17, "随", Trigram.ZHEN, Trigram.DUI, "元亨利贞，无咎。"),
        Hexagram(21, "噬嗑", Trigram.ZHEN, Trigram.LI, "亨。利用狱。"),
        Hexagram(51, "震", Trigram.ZHEN, Trigram.ZHEN, "亨。震来虩虩，笑言哑哑。震惊百里，不丧匕鬯。"),
        Hexagram(42, "益", Trigram.ZHEN, Trigram.XUN, "利有攸往，利涉大川。"),
        Hexagram(3, "屯", Trigram.ZHEN, Trigram.KAN, "元亨利贞。勿用有攸往，利建侯。"),
        Hexagram(27, "颐", Trigram.ZHEN, Trigram.GEN, "贞吉。观颐，自求口实。"),
        Hexagram(24, "复", Trigram.ZHEN, Trigram.KUN, "亨。出入无疾，朋来无咎。反复其道，七日来复。利有攸往。"),
        // 下卦巽
        Hexagram(44, "姤", Trigram.XUN, Trigram.QIAN, "女壮，勿用取女。"),
        Hexagram(28, "大过", Trigram.XUN, Trigram.DUI, "栋桡。利有攸往，亨。"),
        Hexagram(50, "鼎", Trigram.XUN, Trigram.LI, "元吉，亨。"),
        Hexagram(32, "恒", Trigram.XUN, Trigram.ZHEN, "亨，无咎，利贞。利有攸往。"),
        Hexagram(57, "巽", Trigram.XUN, Trigram.XUN, "小亨。利有攸往，利见大人。"),
        Hexagram(48, "井", Trigram.XUN, Trigram.KAN, "改邑不改井，无丧无得。往来井井。汔至亦未繘井，羸其瓶，凶。"),
        Hexagram(18, "蛊", Trigram.XUN, Trigram.GEN, "元亨，利涉大川。先甲三日，后甲三日。"),
        Hexagram(46, "升", Trigram.XUN, Trigram.KUN, "元亨。用见大人，勿恤，南征吉。"),
        // 下卦坎
        Hexagram(6, "讼", Trigram.KAN, Trigram.QIAN, "有孚窒惕，中吉，终凶。利见大人，不利涉大川。"),
        Hexagram(47, "困", Trigram.KAN, Trigram.DUI, "亨。贞，大人吉，无咎。有言不信。"),
        Hexagram(64, "未济", Trigram.KAN, Trigram.LI, "亨。小狐汔济，濡其尾，无攸利。"),
        Hexagram(40, "解", Trigram.KAN, Trigram.ZHEN, "利西南。无所往，其来复吉。有攸往，夙吉。"),
        Hexagram(59, "涣", Trigram.KAN, Trigram.XUN, "亨。王假有庙。利涉大川，利贞。"),
        Hexagram(29, "坎", Trigram.KAN, Trigram.KAN, "习坎，有孚，维心亨，行有尚。"),
        Hexagram(4, "蒙", Trigram.KAN, Trigram.GEN, "亨。匪我求童蒙，童蒙求我。初筮告，再三渎，渎则不告。利贞。"),
        Hexagram(7, "师", Trigram.KAN, Trigram.KUN, "贞，丈人吉，无咎。"),
        // 下卦艮
        Hexagram(33, "遁", Trigram.GEN, Trigram.QIAN, "亨。小利贞。"),
        Hexagram(31, "咸", Trigram.GEN, Trigram.DUI, "亨，利贞。取女吉。"),
        Hexagram(56, "旅", Trigram.GEN, Trigram.LI, "小亨。旅贞吉。"),
        Hexagram(62, "小过", Trigram.GEN, Trigram.ZHEN, "亨，利贞。可小事，不可大事。飞鸟遗之音，不宜上，宜下，大吉。"),
        Hexagram(53, "渐", Trigram.GEN, Trigram.XUN, "女归吉，利贞。"),
        Hexagram(39, "蹇", Trigram.GEN, Trigram.KAN, "利西南，不利东北。利见大人，贞吉。"),
        Hexagram(52, "艮", Trigram.GEN, Trigram.GEN, "艮其背，不获其身。行其庭，不见其人。无咎。"),
        Hexagram(15, "谦", Trigram.GEN, Trigram.KUN, "亨，君子有终。"),
        // 下卦坤
        Hexagram(12, "否", Trigram.KUN, Trigram.QIAN, "否之匪人，不利君子贞，大往小来。"),
        Hexagram(45, "萃", Trigram.KUN, Trigram.DUI, "亨。王假有庙。利见大人，亨，利贞。用大牲吉。利有攸往。"),
        Hexagram(35, "晋", Trigram.KUN, Trigram.LI, "康侯用锡马蕃庶，昼日三接。"),
        Hexagram(16, "豫", Trigram.KUN, Trigram.ZHEN, "利建侯行师。"),
        Hexagram(20, "观", Trigram.KUN, Trigram.XUN, "盥而不荐，有孚颙若。"),
        Hexagram(8, "比", Trigram.KUN, Trigram.KAN, "吉。原筮，元永贞，无咎。不宁方来，后夫凶。"),
        Hexagram(23, "剥", Trigram.KUN, Trigram.GEN, "不利有攸往。"),
        Hexagram(2, "坤", Trigram.KUN, Trigram.KUN, "元亨，利牝马之贞。君子有攸往，先迷后得主，利。西南得朋，东北丧朋。安贞吉。")
    )

    private val byPair: Map<Pair<Trigram, Trigram>, Hexagram> =
        all.associateBy { it.lower to it.upper }

    fun get(lower: Trigram, upper: Trigram): Hexagram =
        byPair.getValue(lower to upper)

    fun all(): List<Hexagram> = all
}