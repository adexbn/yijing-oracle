package com.yijing.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object HistoryStore {

    private const val PREFS = "yijing_history"
    private const val KEY = "records"

    fun save(
        context: Context,
        question: String,
        method: String,
        original: String,
        changed: String,
        movingLine: Int,
        judgment: String
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val obj = JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("question", question)
            put("method", method)
            put("original", original)
            put("changed", changed)
            put("movingLine", movingLine)
            put("judgment", judgment)
        }
        arr.put(obj)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun list(context: Context): List<JSONObject> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        return out.reversed()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
    }

    /** 检测 30 分钟内是否出现过相同提问。 */
    fun hasRecentDuplicate(context: Context, question: String, withinMs: Long = 30 * 60 * 1000): Boolean {
        if (question.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        val now = System.currentTimeMillis()
        for (i in arr.length() - 1 downTo 0) {
            val obj = arr.getJSONObject(i)
            if (now - obj.optLong("time") > withinMs) break
            if (obj.optString("question") == question) return true
        }
        return false
    }
}