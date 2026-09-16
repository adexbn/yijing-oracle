package com.yijing.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yijing.app.core.DivinationResult
import com.yijing.app.core.GuaciBaihua
import com.yijing.app.core.Reading
import com.yijing.app.core.XiangYi
import com.yijing.app.core.YaoDb
import com.yijing.app.ui.CompassView
import com.yijing.app.ui.HexagramLinesView
import com.yijing.app.ui.PerfPanel

class ResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        val result = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("result", DivinationResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("result") as? DivinationResult
        }
        if (result == null) {
            finish()
            return
        }

        val question = intent.getStringExtra("question") ?: ""
        val aiReply = intent.getStringExtra("aiReply")
        val aiMode = intent.getStringExtra("aiMode")
        val aiError = intent.getStringExtra("aiError")
        val aiHint = intent.getStringExtra("aiHint") ?: ""

        findViewById<TextView>(R.id.tvBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.resultName).text = result.original.name
        findViewById<TextView>(R.id.resultMeta).text =
            "变卦 ${result.changed.name} · 动爻第 ${result.movingLine} 爻"

        val compass = findViewById<CompassView>(R.id.compass)
        compass.bind(
            result.original.name,
            result.changed.name,
            result.upper.lines,
            result.lower.lines,
            result.movingLine
        )
        findViewById<HexagramLinesView>(R.id.hexLines)
            .bind(result.upper, result.lower, result.movingLine)

        // 本卦区
        findViewById<TextView>(R.id.origName).text =
            "第${result.original.number}卦 · ${result.original.name}（${result.original.alias}）"
        findViewById<TextView>(R.id.origUnicode).text = result.original.unicodeSymbol.toString()
        findViewById<TextView>(R.id.origXiangyi).text =
            "象义　${XiangYi.of(result.original.number)}"
        findViewById<TextView>(R.id.origJudgment).text =
            "卦辞　${result.original.judgment}"
        findViewById<TextView>(R.id.origBaihua).text =
            "白话　${GuaciBaihua.of(result.original.number)}"

        // 变卦区
        findViewById<TextView>(R.id.changedName).text =
            "第${result.changed.number}卦 · ${result.changed.name}（${result.changed.alias}）"
        findViewById<TextView>(R.id.changedUnicode).text = result.changed.unicodeSymbol.toString()
        findViewById<TextView>(R.id.changedXiangyi).text =
            "象义　${XiangYi.of(result.changed.number)}"
        findViewById<TextView>(R.id.changedJudgment).text =
            "卦辞　${result.changed.judgment}"
        findViewById<TextView>(R.id.changedBaihua).text =
            "白话　${GuaciBaihua.of(result.changed.number)}"

        // 动爻区：爻辞 + 白话（已并入本卦卡）
        val yaoLabel = findViewById<TextView>(R.id.yaoLabel)
        val yaoText = findViewById<TextView>(R.id.yaoText)
        val yaoBaihua = findViewById<TextView>(R.id.yaoBaihua)
        val (yaoYuan, yaoBai) = YaoDb.yao(result.original.name, result.movingLine)
        if (yaoYuan.isNotEmpty()) {
            yaoLabel.text = "第${result.movingLine}爻"
            yaoText.text = "爻辞　$yaoYuan"
            yaoBaihua.text = "白话　$yaoBai"
        } else {
            yaoLabel.visibility = View.GONE
            yaoText.visibility = View.GONE
            yaoBaihua.visibility = View.GONE
        }

        // 提问卡
        val questionCard = findViewById<View>(R.id.questionCard)
        if (question.isNotBlank()) {
            questionCard.visibility = View.VISIBLE
            findViewById<TextView>(R.id.questionText).text = question
        } else {
            questionCard.visibility = View.GONE
        }

        // 解读：结果已在等待页算好，这里只负责展示
        val genericCard = findViewById<View>(R.id.genericCard)
        if (question.isBlank()) {
            genericCard.visibility = View.VISIBLE
            val r = Reading.genericReading(result)
            findViewById<TextView>(R.id.careerText).text = "事业　${r.career}"
            findViewById<TextView>(R.id.loveText).text = "感情　${r.love}"
            findViewById<TextView>(R.id.healthText).text = "健康　${r.health}"
            findViewById<TextView>(R.id.decisionText).text = "抉择　${r.decision}"
        } else if (aiMode != null) {
            genericCard.visibility = View.GONE
            findViewById<View>(R.id.aiCard).visibility = View.VISIBLE
            findViewById<TextView>(R.id.aiLabel).apply {
                text = aiMode
                visibility = View.VISIBLE
            }
            findViewById<TextView>(R.id.aiText).apply {
                visibility = View.VISIBLE
                text = if (aiError.isNullOrBlank()) aiReply ?: "" else aiError
            }
            val hintTv = findViewById<TextView>(R.id.aiHint)
            if (aiHint.isNotBlank()) {
                hintTv.text = aiHint
                hintTv.visibility = View.VISIBLE
            } else {
                hintTv.visibility = View.GONE
            }
        }

        // debug 包：底部追加调试面板（release 包 BuildConfig.DEBUG=false，不显示）。
        // 埋点默认关闭，面板本身要一直挂出来，否则没有入口打开开关。
        if (BuildConfig.DEBUG) {
            val perf = intent.getStringExtra("aiPerf")
                ?: com.yijing.app.core.PerfTrace.lastReport()
            PerfPanel.attach(this, perf)
        }
    }
}