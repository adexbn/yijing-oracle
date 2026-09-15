package com.yijing.app

import android.app.DatePickerDialog
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yijing.app.R
import com.yijing.app.core.Divination
import com.yijing.app.core.DivinationResult
import com.yijing.app.core.HistoryStore
import com.yijing.app.core.LunarCalendar
import com.yijing.app.core.SolarTime
import com.yijing.app.core.Trigram
import com.yijing.app.ui.SealButton
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private var longitudeEast = 120.0

    private lateinit var questionInput: EditText
    private lateinit var solarText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        questionInput = findViewById(R.id.questionInput)
        solarText = findViewById(R.id.solarText)
        val castBtn = findViewById<SealButton>(R.id.castBtn)

        findViewById<TextView>(R.id.btnMine).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateSolarTime()
        requestLocation()
        castBtn.setOnClickListener { castNow() }
        castBtn.setOnLongClickListener {
            showConditionSheet()
            true
        }
    }

    private fun nowTrueSolar(now: Calendar): Pair<Double, Int> {
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val trueSolar = SolarTime.trueSolarMinutes(minute.toDouble(), longitudeEast, now.get(Calendar.DAY_OF_YEAR))
        return trueSolar to SolarTime.shichen(trueSolar)
    }

    private fun updateSolarTime() {
        val now = Calendar.getInstance()
        val (trueSolar, sc) = nowTrueSolar(now)
        val hh = ((trueSolar % 1440).toInt() / 60).let { if (it < 10) "0$it" else "$it" }
        val mm = ((trueSolar % 1440).toInt() % 60).let { if (it < 10) "0$it" else "$it" }
        val sb = StringBuilder("真太阳时 $hh:$mm · ${SolarTime.SHICHEN_NAMES[sc]}时 · 东经$longitudeEast°")
        LunarCalendar.fromGregorian(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)
        )?.let { sb.append("\n四柱：").append(it.bazi) }
        solarText.text = sb.toString()
    }

    private fun castByTime(now: Calendar, hourOverride: Int = -1): DivinationResult {
        val ln = LunarCalendar.fromGregorian(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH), now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)
        )
        if (ln != null) {
            return Divination.fromLunarNumbers(ln.yearZhi, ln.month, ln.day, ln.hourZhi)
        }
        val (_, sc) = nowTrueSolar(now)
        return Divination.fromDateTime(
            now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH), if (hourOverride > 0) hourOverride else sc + 1
        )
    }

    private fun castNow() {
        openResult(castByTime(Calendar.getInstance()))
    }

    private fun openResult(result: DivinationResult) {
        val question = questionInput.text.toString().trim()
        val adjusted = Divination.adjustByQuestion(result, question)
        HistoryStore.save(
            this, question, adjusted.method,
            adjusted.original.name, adjusted.changed.name,
            adjusted.movingLine, adjusted.original.judgment
        )
        if (question.isNotBlank()) {
            // 有提问：先进等待页，本地/云端解读跑完再进结果页，一次性呈现完整结果
            val intent = Intent(this, LoadingActivity::class.java)
            intent.putExtra("result", adjusted)
            intent.putExtra("question", question)
            startActivity(intent)
        } else {
            // 无提问：四维通用解读同步可得，直接进结果页
            val intent = Intent(this, ResultActivity::class.java)
            intent.putExtra("result", adjusted)
            intent.putExtra("question", question)
            startActivity(intent)
        }
    }

    // ---------- 定位 ----------

    private fun requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            readLocation()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
        }
    }

    @Suppress("MissingPermission")
    private fun readLocation() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            null
        }
        if (last != null) {
            longitudeEast = last.longitude
            updateSolarTime()
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                longitudeEast = location.longitude
                updateSolarTime()
            }
        }
        try {
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, null)
        } catch (e: Exception) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            readLocation()
        }
    }

    // ---------- 条件面板 ----------

    private fun showConditionSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_condition, null)
        dialog.setContentView(view)
        view.findViewById<TextView>(R.id.sheet_time).setOnClickListener {
            dialog.dismiss()
            showTimeDialog()
        }
        view.findViewById<TextView>(R.id.sheet_number).setOnClickListener {
            dialog.dismiss()
            showNumberDialog()
        }
        view.findViewById<TextView>(R.id.sheet_direction).setOnClickListener {
            dialog.dismiss()
            showDirectionDialog()
        }
        dialog.show()
    }

    private fun showTimeDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_date, null)
        val dateDisplay = view.findViewById<TextView>(R.id.sheetDateDisplay)
        val now = Calendar.getInstance()
        var y = now.get(Calendar.YEAR)
        var mo = now.get(Calendar.MONTH)
        var d = now.get(Calendar.DAY_OF_MONTH)
        fun refresh() {
            dateDisplay.text = "${y}年${mo + 1}月${d}日"
        }
        refresh()
        dateDisplay.setOnClickListener {
            DatePickerDialog(this, { _, yy, mm, dd ->
                y = yy; mo = mm; d = dd
                refresh()
            }, y, mo, d).show()
        }
        view.findViewById<TextView>(R.id.inputCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.inputOk).setOnClickListener {
            val target = Calendar.getInstance().apply {
                set(y, mo, d, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            }
            dialog.dismiss()
            openResult(castByTime(target))
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showNumberDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_input, null)
        val title = view.findViewById<TextView>(R.id.sheetInputTitle)
        val e1 = view.findViewById<EditText>(R.id.sheetIn1)
        val e2 = view.findViewById<EditText>(R.id.sheetIn2)
        val e3 = view.findViewById<EditText>(R.id.sheetIn3)
        title.text = "报数起卦"
        e1.hint = "第一数"; e2.hint = "第二数"; e3.hint = "第三数"
        view.findViewById<TextView>(R.id.inputCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.inputOk).setOnClickListener {
            val n1 = e1.text.toString().toIntOrNull()
            val n2 = e2.text.toString().toIntOrNull()
            val n3 = e3.text.toString().toIntOrNull()
            if (n1 == null || n2 == null || n3 == null) {
                Toast.makeText(this, "请填满三个数", Toast.LENGTH_SHORT).show()
            } else {
                dialog.dismiss()
                openResult(Divination.fromNumbers(n1, n2, n3))
            }
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showDirectionDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_direction, null)
        val container = view.findViewById<LinearLayout>(R.id.directionList)

        // 九宫格：上北下南，中宫为点格
        val grid = listOf(
            "西北", "北", "东北",
            "西", null, "东",
            "西南", "南", "东南"
        )

        for (row in 0 until 3) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            container.addView(rowLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })

            for (col in 0 until 3) {
                val dir = grid[row * 3 + col]
                val cell = TextView(this).apply {
                    gravity = android.view.Gravity.CENTER
                    setMinHeight(dp(64))
                    if (dir == null) {
                        text = "·"
                        textSize = 30f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_muted))
                    } else {
                        val tg = Trigram.fromDirection(dir)
                        text = "$dir\n${tg.symbol}${tg.label}·${tg.nature}"
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink))
                        setBackgroundResource(R.drawable.bg_card)
                        setOnClickListener {
                            dialog.dismiss()
                            val (_, sc) = nowTrueSolar(Calendar.getInstance())
                            openResult(Divination.fromDirection(dir, sc))
                        }
                    }
                }
                val cellLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (col != 0) cellLp.marginStart = dp(8)
                rowLayout.addView(cell, cellLp)
            }
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_LOCATION = 1001
    }
}