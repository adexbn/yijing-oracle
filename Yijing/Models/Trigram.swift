import Foundation

/// 八卦：先天卦序 + 三爻（自下而上，1=阳 / 0=阴）。
enum Trigram: Int, CaseIterable {
    case qian = 1
    case dui = 2
    case li = 3
    case zhen = 4
    case xun = 5
    case kan = 6
    case gen = 7
    case kun = 8

    var label: String {
        switch self {
        case .qian: return "乾"
        case .dui: return "兑"
        case .li: return "离"
        case .zhen: return "震"
        case .xun: return "巽"
        case .kan: return "坎"
        case .gen: return "艮"
        case .kun: return "坤"
        }
    }

    var lines: Int {
        switch self {
        case .qian: return 0b111
        case .dui: return 0b110
        case .li: return 0b101
        case .zhen: return 0b100
        case .xun: return 0b011
        case .kan: return 0b010
        case .gen: return 0b001
        case .kun: return 0b000
        }
    }

    var symbol: String {
        switch self {
        case .qian: return "☰"
        case .dui: return "☱"
        case .li: return "☲"
        case .zhen: return "☳"
        case .xun: return "☴"
        case .kan: return "☵"
        case .gen: return "☶"
        case .kun: return "☷"
        }
    }

    var direction: String {
        switch self {
        case .qian: return "西北"
        case .dui: return "西"
        case .li: return "南"
        case .zhen: return "东"
        case .xun: return "东南"
        case .kan: return "北"
        case .gen: return "东北"
        case .kun: return "西南"
        }
    }

    var nature: String {
        switch self {
        case .qian: return "天"
        case .dui: return "泽"
        case .li: return "火"
        case .zhen: return "雷"
        case .xun: return "风"
        case .kan: return "水"
        case .gen: return "山"
        case .kun: return "地"
        }
    }

    static func fromNumber(_ n: Int) -> Trigram {
        let m = ((((n - 1) % 8) + 8) % 8) + 1
        return Trigram(rawValue: m)!
    }

    static func fromLines(_ l: Int) -> Trigram {
        return allCases.first { $0.lines == l }!
    }

    static func fromDirection(_ d: String) -> Trigram {
        return allCases.first { $0.direction == d }!
    }
}