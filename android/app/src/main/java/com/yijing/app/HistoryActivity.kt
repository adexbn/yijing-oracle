package com.yijing.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yijing.app.core.HistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        val list = findViewById<LinearLayout>(R.id.historyList)
        findViewById<TextView>(R.id.btnClear).setOnClickListener {
            HistoryStore.clear(this)
            list.removeAllViews()
            showEmpty(list)
        }

        render(list)
    }

    private fun render(list: LinearLayout) {
        val records = HistoryStore.list(this)
        if (records.isEmpty()) {
            showEmpty(list)
            return
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (rec in records) {
            val question = rec.optString("question")
            val method = rec.optString("method")
            val original = rec.optString("original")
            val changed = rec.optString("changed")
            val moving = rec.optInt("movingLine", 0)
            val time = fmt.format(Date(rec.optLong("time")))

            val tv = TextView(this).apply {
                text = buildString {
                    append(method).append(" · ").append(time)
                    if (question.isNotBlank()) append("\n问：").append(question)
                    append("\n").append(original)
                    if (moving > 0) append(" · 动爻").append(moving)
                    append("  →  ").append(changed)
                }
                setTextColor(getColor(R.color.ink))
                textSize = 15f
                setLineSpacing(0f, 1.1f)
                setPadding(0, 20, 0, 20)
            }
            list.addView(tv)
            list.addView(divider())
        }
    }

    private fun divider(): View {
        val h = (1 * resources.displayMetrics.density).toInt()
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h
            )
            setBackgroundColor(getColor(R.color.divider))
        }
    }

    private fun showEmpty(list: LinearLayout) {
        val tv = TextView(this).apply {
            text = "暂无记录，先起一卦吧。"
            setTextColor(getColor(R.color.ink_muted))
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        list.addView(tv)
    }
}