import Foundation

struct DivinationResult {
    let method: String
    let lower: Trigram
    let upper: Trigram
    let movingLine: Int
    let original: Hexagram
    let changed: Hexagram
}

enum Divination {

    static func build(_ method: String, _ lower: Trigram, _ upper: Trigram, _ movingLine: Int) -> DivinationResult {
        let m = movingLine <= 0 ? 6 : ((movingLine - 1) % 6) + 1
        let original = HexagramDb.get(lower: lower, upper: upper)
        let changed = flip(original, m)
        return DivinationResult(method: method, lower: lower, upper: upper, movingLine: m, original: original, changed: changed)
    }

    /// 翻动某爻（自下而上 1..6）得到变卦。
    private static func flip(_ h: Hexagram, _ line: Int) -> Hexagram {
        let mask = 1 << (line - 1)
        let lower: Trigram
        let upper: Trigram
        if line <= 3 {
            lower = Trigram.fromLines(h.lower.lines ^ mask)
            upper = h.upper
        } else {
            lower = h.lower
            upper = Trigram.fromLines(h.upper.lines ^ (1 << (line - 4)))
        }
        return HexagramDb.get(lower: lower, upper: upper)
    }

    /// 有提问时，以提问字数修正动爻（本卦不变，变卦随动爻而变）。
    static func adjustByQuestion(_ result: DivinationResult, question: String) -> DivinationResult {
        let len = question.trimmingCharacters(in: .whitespacesAndNewlines).count
        if len == 0 { return result }
        return build(result.method, result.lower, result.upper, result.movingLine + len)
    }

    /// 时间起卦（梅花易数）：年+月+日 → 上卦；年+月+日+时 → 下卦/动爻。
    static func fromDateTime(_ year: Int, _ month: Int, _ day: Int, _ hour: Int) -> DivinationResult {
        let sum1 = year + month + day
        let sum2 = sum1 + hour
        let upper = Trigram.fromNumber(sum1)
        let lower = Trigram.fromNumber(sum2)
        let moving = ((sum2 - 1) % 6) + 1
        return build("时间起卦", lower, upper, moving)
    }

    /// 报数起卦：三数 → 上卦 / 下卦 / 动爻。
    static func fromNumbers(_ n1: Int, _ n2: Int, _ n3: Int) -> DivinationResult {
        let upper = Trigram.fromNumber(n1)
        let lower = Trigram.fromNumber(n2)
        let moving = ((n3 - 1) % 6) + 1
        return build("报数起卦", lower, upper, moving)
    }

    /// 方位起卦：所向方位 → 上卦；时辰数 → 下卦；方位数+时辰 → 动爻。
    static func fromDirection(_ direction: String, _ shichenIndex: Int) -> DivinationResult {
        let upper = Trigram.fromDirection(direction)
        let hourNum = shichenIndex + 1
        let lower = Trigram.fromNumber(hourNum)
        let moving = ((upper.rawValue + hourNum - 1) % 6) + 1
        return build("方位起卦", lower, upper, moving)
    }

    /// 时间起卦（农历）：年支 + 月 + 日 -> 上卦；再 + 时支 -> 下卦与动爻。
    static func fromLunarNumbers(yearZhi: Int, month: Int, day: Int, hourZhi: Int) -> DivinationResult {
        let sum1 = yearZhi + month + day
        let sum2 = sum1 + hourZhi
        let upper = Trigram.fromNumber(sum1)
        let lower = Trigram.fromNumber(sum2)
        let moving = ((sum2 - 1) % 6) + 1
        return build("时间起卦（农历）", lower, upper, moving)
    }
}