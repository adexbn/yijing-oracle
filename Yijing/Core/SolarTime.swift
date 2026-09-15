import Foundation

/// 真太阳时：平太阳时 + 经度订正 + 时差（equation of time）。
/// 中国标准时以东经 120° 为基准。
enum SolarTime {

    static let standardMeridian = 120.0

    /// 时差方程，返回分钟。dayOfYear 取 1..366。
    static func equationOfTime(dayOfYear: Int) -> Double {
        let b = 2.0 * Double.pi * Double(dayOfYear - 81) / 364.0
        return 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
    }

    /// 由钟表时刻（分钟，当日 0:00 起）换算真太阳时（分钟）。
    static func trueSolarMinutes(stdClockMinutes: Double, longitudeEast: Double, dayOfYear: Int) -> Double {
        let longitudeCorrection = 4.0 * (longitudeEast - standardMeridian)
        return stdClockMinutes + longitudeCorrection + equationOfTime(dayOfYear: dayOfYear)
    }

    /// 时辰（子丑寅卯…）：真太阳时每 2 小时为一个时辰，0=子时，11=亥时。
    static func shichen(trueSolarMinutes: Double) -> Int {
        let normalized = ((trueSolarMinutes.truncatingRemainder(dividingBy: 1440)) + 1440).truncatingRemainder(dividingBy: 1440)
        return Int(normalized / 120.0) % 12
    }

    static let shichenNames = ["子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"]
}