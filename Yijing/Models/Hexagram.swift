import Foundation

struct Hexagram: Hashable {
    let number: Int
    let name: String
    let lower: Trigram
    let upper: Trigram
    let judgment: String

    init(_ number: Int, _ name: String, _ lower: Trigram, _ upper: Trigram, _ judgment: String) {
        self.number = number
        self.name = name
        self.lower = lower
        self.upper = upper
        self.judgment = judgment
    }

    var symbol: String { upper.symbol + lower.symbol }

    /// 别名（卦象名）：纯卦为「离为火」，重卦为「火风鼎」式。
    var alias: String {
        if upper == lower { return "\(name)为\(upper.nature)" }
        return "\(upper.nature)\(lower.nature)\(name)"
    }

    /// 六十四卦 Unicode 符号（自 U+4DC0 起按卦序）。
    var unicodeSymbol: Character {
        return Character(UnicodeScalar(0x4DC0 + number - 1)!)
    }
}