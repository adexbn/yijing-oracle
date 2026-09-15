import Foundation
import LunarSwift

/// 农历与四柱：基于 6tail/lunar-swift 纯 Swift 库（无原生依赖）。
/// 公历 -> 农历年月日 + 八字（年/月/日/时四柱），用于梅花易数时间起卦。
enum LunarCalendar {

    struct TimeNumbers {
        let yearZhi: Int    // 年支序 1..12（子=1）
        let month: Int      // 农历月 1..12
        let day: Int        // 农历日 1..30
        let hourZhi: Int    // 时支序 1..12（子=1）
        let bazi: String    // 四柱 "年柱 月柱 日柱 时柱"
    }

    private static let zhiOrder: [String: Int] = [
        "子": 1, "丑": 2, "寅": 3, "卯": 4, "辰": 5, "巳": 6,
        "午": 7, "未": 8, "申": 9, "酉": 10, "戌": 11, "亥": 12
    ]

    static func fromGregorian(year: Int, month: Int, day: Int, hour: Int, minute: Int) -> TimeNumbers? {
        let solar = Solar.fromYmdHms(year: year, month: month, day: day, hour: hour, minute: minute, second: 0)
        let lunar = solar.lunar
        let ec = EightChar.fromLunar(lunar: lunar)
        guard let yz = zhiOrder[ec.yearZhi], let hz = zhiOrder[ec.timeZhi] else { return nil }
        let lm = lunar.month
        return TimeNumbers(
            yearZhi: yz,
            month: lm < 0 ? -lm : lm,
            day: lunar.day,
            hourZhi: hz,
            bazi: "\(ec.year) \(ec.month) \(ec.day) \(ec.time)"
        )
    }
}