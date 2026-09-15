import Foundation

/// 历史记录条目（与 Android HistoryStore 字段对齐）。
struct HistoryRecord: Codable, Identifiable {
    var id: TimeInterval { time }
    var time: TimeInterval
    var question: String
    var method: String
    var original: String
    var changed: String
    var movingLine: Int
    var judgment: String
}

/// 起卦历史持久化：JSON 数组存 UserDefaults。
enum HistoryStore {

    private static let key = "yijing.history"

    static func save(
        question: String,
        method: String,
        original: String,
        changed: String,
        movingLine: Int,
        judgment: String
    ) {
        var records = list()
        records.append(HistoryRecord(
            time: Date().timeIntervalSince1970,
            question: question,
            method: method,
            original: original,
            changed: changed,
            movingLine: movingLine,
            judgment: judgment
        ))
        write(records)
    }

    /// 最新在前。
    static func list() -> [HistoryRecord] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let arr = try? JSONDecoder().decode([HistoryRecord].self, from: data) else { return [] }
        return arr.reversed()
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }

    /// 检测近 30 分钟内是否出现过相同提问。
    static func hasRecentDuplicate(_ question: String, withinMs: TimeInterval = 30 * 60 * 1000) -> Bool {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        if q.isEmpty { return false }
        let now = Date().timeIntervalSince1970 * 1000
        for rec in raw() {
            if now - rec.time * 1000 > withinMs { break }
            if rec.question == q { return true }
        }
        return false
    }

    // 按写入顺序（旧 -> 新）读取。
    private static func raw() -> [HistoryRecord] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let arr = try? JSONDecoder().decode([HistoryRecord].self, from: data) else { return [] }
        return arr
    }

    private static func write(_ records: [HistoryRecord]) {
        guard let data = try? JSONEncoder().encode(records) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}