import Foundation

/// 通用解卦：无提问时，按 事业 / 感情 / 健康 / 抉择 四维输出解读。
struct GenericReading {
    let career: String
    let love: String
    let health: String
    let decision: String
}

enum Reading {

    // ---------- 五行 ----------

    private static func wuxing(_ t: Trigram) -> String {
        switch t {
        case .qian, .dui: return "金"
        case .zhen, .xun: return "木"
        case .kan: return "水"
        case .li: return "火"
        case .kun, .gen: return "土"
        }
    }

    private static func bodyPart(_ t: Trigram) -> String {
        switch t {
        case .qian: return "头/肺"
        case .kun: return "腹/脾"
        case .zhen: return "足/肝"
        case .xun: return "股/胆"
        case .kan: return "耳/肾"
        case .li: return "目/心"
        case .gen: return "手/胃"
        case .dui: return "口/肺"
        }
    }

    private static let wuxingOrder = ["金", "水", "木", "火", "土"]

    /// 体用关系：下卦为体（己），上卦为用（彼/环境）。
    private static func tiYong(_ lower: Trigram, _ upper: Trigram) -> String {
        let ti = wuxing(lower)
        let yong = wuxing(upper)
        if ti == yong { return "比和" }
        guard let tiIdx = wuxingOrder.firstIndex(of: ti),
              let yongIdx = wuxingOrder.firstIndex(of: yong) else { return "比和" }
        if (yongIdx + 1) % 5 == tiIdx { return "用生体" }
        if (tiIdx + 1) % 5 == yongIdx { return "体生用" }
        if (yongIdx + 2) % 5 == tiIdx { return "用克体" }
        return "体克用"
    }

    private static func isJi(_ h: Hexagram) -> Bool {
        let j = h.judgment
        return j.contains("吉") || j.contains("亨") || j.contains("利") ||
            j.hasPrefix("元") || j.contains("无咎")
    }

    private static func isXiong(_ h: Hexagram) -> Bool {
        let j = h.judgment
        return j.contains("凶") || j.contains("咎") || j.contains("悔") ||
            j.contains("吝") || j.contains("不利")
    }

    // ---------- 通用解读 ----------

    static func genericReading(_ r: DivinationResult) -> GenericReading {
        let ty = tiYong(r.lower, r.upper)
        return GenericReading(
            career: careerReading(r.original, r.changed, r.movingLine, ty),
            love: loveReading(r.original, r.changed, r.lower, r.upper, ty),
            health: healthReading(r.lower, r.upper, r.movingLine, ty),
            decision: decisionReading(r.original, r.changed, r.movingLine, ty)
        )
    }

    private static func careerReading(_ orig: Hexagram, _ chg: Hexagram, _ ml: Int, _ ty: String) -> String {
        let base = "\(orig.name)卦主\(careerKeyword(orig))。"
        let move = ml > 3
            ? "动爻在上卦，气机向上，宜主动求变、争取机会。"
            : "动爻在下卦，气机未浮，宜深耕积累、静待时机。"
        let tyAdvice: String
        switch ty {
        case "用生体": tyAdvice = "外部环境对你有利，贵人运佳，顺势而为。"
        case "体生用": tyAdvice = "你付出较多，劳而有获，但需注意节奏。"
        case "用克体": tyAdvice = "外部压力较大，不宜硬碰，迂回为上。"
        case "体克用": tyAdvice = "你有掌控力，宜主动出击，成事在己。"
        default: tyAdvice = "内外平衡，按部就班即可。"
        }
        let chgNote: String
        if isJi(chg) && !isXiong(chg) { chgNote = "变卦\(chg.name)卦向好，坚持可成。" }
        else if isXiong(chg) && !isJi(chg) { chgNote = "变卦\(chg.name)卦有阻，短期不宜冒进。" }
        else { chgNote = "" }
        return "\(base)\(move)\(tyAdvice)\(chgNote)"
    }

    private static func loveReading(_ orig: Hexagram, _ chg: Hexagram, _ lower: Trigram, _ upper: Trigram, _ ty: String) -> String {
        let base = "\(orig.name)卦在感情上\(loveKeyword(orig))。"
        let tyDesc: String
        switch ty {
        case "用生体": tyDesc = "对方（\(upper.label)卦象）对你用心，感情滋养。"
        case "体生用": tyDesc = "你（\(lower.label)卦象）付出更多，乐在其中但有倦时。"
        case "用克体": tyDesc = "对方或环境让你感到压力，需坦诚沟通。"
        case "体克用": tyDesc = "你处于主导地位，注意别让对方感到被压制。"
        default: tyDesc = "彼此势均力敌，相敬如宾。"
        }
        let chgNote: String
        if isJi(chg) && !isXiong(chg) { chgNote = "变卦\(chg.name)卦吉，关系向好发展。" }
        else if isXiong(chg) && !isJi(chg) { chgNote = "变卦\(chg.name)卦不利，需多些耐心磨合。" }
        else { chgNote = "" }
        return "\(base)\(tyDesc)\(chgNote)"
    }

    private static func healthReading(_ lower: Trigram, _ upper: Trigram, _ ml: Int, _ ty: String) -> String {
        let bp = bodyPart(ml > 3 ? upper : lower)
        let area = ml > 3 ? "上半身" : "下半身"
        let base = "\(wuxing(lower))性体质。"
        let focus = "动爻在\(ml > 3 ? "上" : "下")卦，近期留意\(area)（\(bp)）。"
        let tyNote: String
        switch ty {
        case "用克体": tyNote = "外部环境对健康不利，注意劳逸结合。"
        case "体克用": tyNote = "体质尚可，但消耗较大，宜补养。"
        case "体生用": tyNote = "容易透支，注意睡眠和饮食规律。"
        case "用生体": tyNote = "身心状态良好，保持即可。"
        default: tyNote = "机能平稳，适度运动有益。"
        }
        return "\(base)\(focus)\(tyNote)"
    }

    private static func decisionReading(_ orig: Hexagram, _ chg: Hexagram, _ ml: Int, _ ty: String) -> String {
        let move = ml > 3
            ? "动爻在上卦，宜动不宜静——若在两难之间，选更难的那条路，安逸反生悔。"
            : "动爻在下卦，宜守不宜攻——若在两难之间，选那个已经坚持更久的方向，时机未到勿轻弃。"

        let origJi = isJi(orig) && !isXiong(orig)
        let chgJi = isJi(chg) && !isXiong(chg)
        let trend: String
        switch true {
        case origJi && !chgJi: trend = "本卦吉而变卦不吉，见好就收，不宜贪多。"
        case !origJi && chgJi: trend = "本卦不吉而变卦向吉，再坚持一下，转折不远。"
        case origJi && chgJi: trend = "本卦变卦皆吉，进退皆可，随心意而行。"
        case !origJi && !chgJi: trend = "本卦变卦皆不吉，此时不宜做重大决定，宜静观其变。"
        default: trend = "卦象中和，谨慎权衡即可。"
        }
        let tyAdvice: String
        switch ty {
        case "体克用", "用克体": tyAdvice = "宜独立决策，少听他人干扰。"
        case "用生体", "体生用": tyAdvice = "宜与人合作，借力而行。"
        default: tyAdvice = "可独行亦可结伴，看具体情况。"
        }
        return "\(move)\(trend)\(tyAdvice)"
    }

    private static func careerKeyword(_ hex: Hexagram) -> String {
        switch hex.number {
        case 1: return "开创进取"
        case 2: return "厚德载物"
        case 3: return "万事开头难"
        case 4: return "启蒙待发"
        case 5: return "耐心等待"
        case 6: return "避免争执"
        case 7: return "统率有方"
        case 8: return "亲附团结"
        case 9: return "小有积蓄"
        case 10: return "谨言慎行"
        case 11: return "通泰顺遂"
        case 12: return "闭塞不通"
        case 13: return "与人合作"
        case 14: return "丰盛收获"
        case 15: return "谦虚受益"
        case 16: return "顺势而动"
        case 17: return "随缘而行"
        case 18: return "整顿旧弊"
        case 19: return "亲临督导"
        case 20: return "观察待机"
        case 21: return "果断裁决"
        case 22: return "文饰包装"
        case 23: return "剥落衰退"
        case 24: return "一阳来复"
        case 25: return "顺其自然"
        case 26: return "厚积薄发"
        case 27: return "自食其力"
        case 28: return "过犹不及"
        case 29: return "险中求进"
        case 30: return "依附光明"
        case 31: return "感而遂通"
        case 32: return "持之以恒"
        case 33: return "退避保全"
        case 34: return "强盛勿骄"
        case 35: return "蒸蒸日上"
        case 36: return "韬光养晦"
        case 37: return "齐家为先"
        case 38: return "求同存异"
        case 39: return "知难而退"
        case 40: return "解脱困境"
        case 41: return "损己利人"
        case 42: return "增益其德"
        case 43: return "决断去留"
        case 44: return "不期而遇"
        case 45: return "聚集人才"
        case 46: return "步步高升"
        case 47: return "困而知变"
        case 48: return "固本培元"
        case 49: return "变革图新"
        case 50: return "革故鼎新"
        case 51: return "临危不乱"
        case 52: return "知止不殆"
        case 53: return "循序渐进"
        case 54: return "名分不正"
        case 55: return "盛极而衰"
        case 56: return "客居他乡"
        case 57: return "柔顺服从"
        case 58: return "和悦相处"
        case 59: return "涣散重整"
        case 60: return "节制有度"
        case 61: return "诚信为本"
        case 62: return "小处着手"
        case 63: return "功成防变"
        case 64: return "尚未完成"
        default: return "顺势而为"
        }
    }

    private static func loveKeyword(_ hex: Hexagram) -> String {
        switch hex.number {
        case 31: return "感应相吸，自然生情"
        case 32: return "长久之道，恒心为贵"
        case 44: return "不期而遇的缘分"
        case 54: return "需注意名分与位置"
        case 37: return "家和万事兴"
        case 53: return "细水长流，不可急躁"
        case 11: return "天地交泰，感情和顺"
        case 12: return "沟通不畅，需破冰"
        case 58: return "愉悦相处，以和为贵"
        case 17: return "随缘就势，莫强求"
        case 21: return "有阻碍需化解"
        case 38: return "观念不同但可调和"
        case 41: return "有舍才有得"
        case 42: return "彼此增益，共同成长"
        default: return "看卦象而定"
        }
    }
}