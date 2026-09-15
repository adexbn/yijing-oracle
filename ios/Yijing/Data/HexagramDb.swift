import Foundation

/// 六十四卦离线库。由（下卦, 上卦）可查本卦；卦辞取通行本「卦辞」原文。
enum HexagramDb {
    static let all: [Hexagram] = [
        Hexagram(1, "乾", .qian, .qian, "元亨利贞。"),
        Hexagram(43, "夬", .qian, .dui, "扬于王庭，孚号有厉。告自邑，不利即戎。利有攸往。"),
        Hexagram(14, "大有", .qian, .li, "元亨。"),
        Hexagram(34, "大壮", .qian, .zhen, "利贞。"),
        Hexagram(9, "小畜", .qian, .xun, "亨。密云不雨，自我西郊。"),
        Hexagram(5, "需", .qian, .kan, "有孚，光亨，贞吉。利涉大川。"),
        Hexagram(26, "大畜", .qian, .gen, "利贞。不家食吉。利涉大川。"),
        Hexagram(11, "泰", .qian, .kun, "小往大来，吉亨。"),
        Hexagram(10, "履", .dui, .qian, "履虎尾，不咥人，亨。"),
        Hexagram(58, "兑", .dui, .dui, "亨，利贞。"),
        Hexagram(38, "睽", .dui, .li, "小事吉。"),
        Hexagram(54, "归妹", .dui, .zhen, "征凶，无攸利。"),
        Hexagram(61, "中孚", .dui, .xun, "豚鱼吉。利涉大川，利贞。"),
        Hexagram(60, "节", .dui, .kan, "亨。苦节不可贞。"),
        Hexagram(41, "损", .dui, .gen, "有孚，元吉，无咎，可贞。利有攸往。曷之用？二簋可用享。"),
        Hexagram(19, "临", .dui, .kun, "元亨利贞。至于八月有凶。"),
        Hexagram(13, "同人", .li, .qian, "同人于野，亨。利涉大川，利君子贞。"),
        Hexagram(49, "革", .li, .dui, "己日乃孚。元亨利贞，悔亡。"),
        Hexagram(30, "离", .li, .li, "利贞，亨。畜牝牛吉。"),
        Hexagram(55, "丰", .li, .zhen, "亨。王假之，勿忧，宜日中。"),
        Hexagram(37, "家人", .li, .xun, "利女贞。"),
        Hexagram(63, "既济", .li, .kan, "亨小，利贞。初吉终乱。"),
        Hexagram(22, "贲", .li, .gen, "亨。小利有攸往。"),
        Hexagram(36, "明夷", .li, .kun, "利艰贞。"),
        Hexagram(25, "无妄", .zhen, .qian, "元亨利贞。其匪正有眚，不利有攸往。"),
        Hexagram(17, "随", .zhen, .dui, "元亨利贞，无咎。"),
        Hexagram(21, "噬嗑", .zhen, .li, "亨。利用狱。"),
        Hexagram(51, "震", .zhen, .zhen, "亨。震来虩虩，笑言哑哑。震惊百里，不丧匕鬯。"),
        Hexagram(42, "益", .zhen, .xun, "利有攸往，利涉大川。"),
        Hexagram(3, "屯", .zhen, .kan, "元亨利贞。勿用有攸往，利建侯。"),
        Hexagram(27, "颐", .zhen, .gen, "贞吉。观颐，自求口实。"),
        Hexagram(24, "复", .zhen, .kun, "亨。出入无疾，朋来无咎。反复其道，七日来复。利有攸往。"),
        Hexagram(44, "姤", .xun, .qian, "女壮，勿用取女。"),
        Hexagram(28, "大过", .xun, .dui, "栋桡。利有攸往，亨。"),
        Hexagram(50, "鼎", .xun, .li, "元吉，亨。"),
        Hexagram(32, "恒", .xun, .zhen, "亨，无咎，利贞。利有攸往。"),
        Hexagram(57, "巽", .xun, .xun, "小亨。利有攸往，利见大人。"),
        Hexagram(48, "井", .xun, .kan, "改邑不改井，无丧无得。往来井井。汔至亦未繘井，羸其瓶，凶。"),
        Hexagram(18, "蛊", .xun, .gen, "元亨，利涉大川。先甲三日，后甲三日。"),
        Hexagram(46, "升", .xun, .kun, "元亨。用见大人，勿恤，南征吉。"),
        Hexagram(6, "讼", .kan, .qian, "有孚窒惕，中吉，终凶。利见大人，不利涉大川。"),
        Hexagram(47, "困", .kan, .dui, "亨。贞，大人吉，无咎。有言不信。"),
        Hexagram(64, "未济", .kan, .li, "亨。小狐汔济，濡其尾，无攸利。"),
        Hexagram(40, "解", .kan, .zhen, "利西南。无所往，其来复吉。有攸往，夙吉。"),
        Hexagram(59, "涣", .kan, .xun, "亨。王假有庙。利涉大川，利贞。"),
        Hexagram(29, "坎", .kan, .kan, "习坎，有孚，维心亨，行有尚。"),
        Hexagram(4, "蒙", .kan, .gen, "亨。匪我求童蒙，童蒙求我。初筮告，再三渎，渎则不告。利贞。"),
        Hexagram(7, "师", .kan, .kun, "贞，丈人吉，无咎。"),
        Hexagram(33, "遁", .gen, .qian, "亨。小利贞。"),
        Hexagram(31, "咸", .gen, .dui, "亨，利贞。取女吉。"),
        Hexagram(56, "旅", .gen, .li, "小亨。旅贞吉。"),
        Hexagram(62, "小过", .gen, .zhen, "亨，利贞。可小事，不可大事。飞鸟遗之音，不宜上，宜下，大吉。"),
        Hexagram(53, "渐", .gen, .xun, "女归吉，利贞。"),
        Hexagram(39, "蹇", .gen, .kan, "利西南，不利东北。利见大人，贞吉。"),
        Hexagram(52, "艮", .gen, .gen, "艮其背，不获其身。行其庭，不见其人。无咎。"),
        Hexagram(15, "谦", .gen, .kun, "亨，君子有终。"),
        Hexagram(12, "否", .kun, .qian, "否之匪人，不利君子贞，大往小来。"),
        Hexagram(45, "萃", .kun, .dui, "亨。王假有庙。利见大人，亨，利贞。用大牲吉。利有攸往。"),
        Hexagram(35, "晋", .kun, .li, "康侯用锡马蕃庶，昼日三接。"),
        Hexagram(16, "豫", .kun, .zhen, "利建侯行师。"),
        Hexagram(20, "观", .kun, .xun, "盥而不荐，有孚颙若。"),
        Hexagram(8, "比", .kun, .kan, "吉。原筮，元永贞，无咎。不宁方来，后夫凶。"),
        Hexagram(23, "剥", .kun, .gen, "不利有攸往。"),
        Hexagram(2, "坤", .kun, .kun, "元亨，利牝马之贞。君子有攸往，先迷后得主，利。西南得朋，东北丧朋。安贞吉。")
    ]

    private static let byPair: [PairKey: Hexagram] = {
        var map: [PairKey: Hexagram] = [:]
        for h in all { map[PairKey(lower: h.lower, upper: h.upper)] = h }
        return map
    }()

    static func get(lower: Trigram, upper: Trigram) -> Hexagram {
        return byPair[PairKey(lower: lower, upper: upper)]!
    }
}

private struct PairKey: Hashable {
    let lower: Trigram
    let upper: Trigram
}