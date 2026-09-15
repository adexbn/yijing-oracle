package com.yijing.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.yijing.app.core.AiClient
import com.yijing.app.core.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var modelBtn: MaterialButton
    private var downloading = false

    /** 抑制 Spinner 初始 setSelection 触发的回调，避免覆盖已保存的 model 名。 */
    private var suppressSpinner = true

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        val providerSpinner = findViewById<Spinner>(R.id.providerSpinner)
        val baseUrlInput = findViewById<EditText>(R.id.baseUrlInput)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val modelInput = findViewById<EditText>(R.id.modelInput)
        val cloudSwitch = findViewById<SwitchMaterial>(R.id.cloudSwitch)

        modelStatus = findViewById(R.id.modelStatus)
        modelProgress = findViewById(R.id.modelProgress)
        modelBtn = findViewById(R.id.modelBtn)

        val providers = AiClient.PROVIDERS.keys.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = adapter

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinner) {
                    // 忽略初始化时 setSelection 触发的首次回调，之后再响应用户选择
                    suppressSpinner = false
                    return
                }
                val p = providers[position]
                val (defUrl, defModel) = AiClient.PROVIDERS[p] ?: ("" to "")
                baseUrlInput.setText(defUrl)
                modelInput.setText(defModel)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val config = AiClient.loadConfig(this)
        providerSpinner.setSelection(providers.indexOf(config.provider).coerceAtLeast(0))
        baseUrlInput.setText(config.baseUrl)
        apiKeyInput.setText(config.apiKey)
        modelInput.setText(config.model)
        cloudSwitch.isChecked = config.cloudEnabled

        setupModelSection()
        refreshModelStatus()

        findViewById<MaterialButton>(R.id.saveBtn).setOnClickListener {
            val provider = providers[providerSpinner.selectedItemPosition]
            val (defUrl, defModel) = AiClient.PROVIDERS[provider] ?: ("" to "")
            AiClient.saveConfig(
                this,
                AiClient.Config(
                    provider = provider,
                    baseUrl = baseUrlInput.text.toString().trim().ifBlank { defUrl },
                    apiKey = apiKeyInput.text.toString().trim(),
                    model = modelInput.text.toString().trim().ifBlank { defModel },
                    cloudEnabled = cloudSwitch.isChecked
                )
            )
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupModelSection() {
        modelBtn.setOnClickListener {
            if (downloading) return@setOnClickListener
            if (ModelManager.isDownloaded(this)) {
                ModelManager.delete(this)
                refreshModelStatus()
                Toast.makeText(this, "已删除本地模型", Toast.LENGTH_SHORT).show()
            } else {
                startDownload()
            }
        }

        findViewById<TextView>(R.id.originalLink).text = ModelManager.MODEL_URL
        findViewById<TextView>(R.id.mirrorLink).text = ModelManager.MODEL_URL_MIRROR
        findViewById<TextView>(R.id.copyOriginal).setOnClickListener {
            copyText(ModelManager.MODEL_URL)
        }
        findViewById<TextView>(R.id.copyMirror).setOnClickListener {
            copyText(ModelManager.MODEL_URL_MIRROR)
        }
        findViewById<MaterialButton>(R.id.importBtn).setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("url", text))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun importModel(uri: Uri) {
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val dest = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        ModelManager.importFrom(this@SettingsActivity, input)
                    } ?: throw Exception("无法读取所选文件")
                }
                runOnUiThread {
                    refreshModelStatus()
                    val msg = if (ModelManager.isDownloaded(this@SettingsActivity)) {
                        "导入成功：${dest.name}"
                    } else {
                        "文件已写入，但长度偏小，请确认选择的是约 1.2GB 的模型文件"
                    }
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun refreshModelStatus() {
        if (downloading) {
            modelStatus.text = "下载中…"
            modelBtn.isEnabled = false
            modelBtn.text = "下载中"
            return
        }
        modelBtn.isEnabled = true
        val file = ModelManager.modelFile(this)
        if (ModelManager.isDownloaded(this)) {
            modelStatus.text = "已下载 · ${formatSize(file.length())}"
            modelBtn.text = "删除模型"
        } else {
            modelStatus.text = "未下载（约 1.2GB，建议在 Wi-Fi 下进行）"
            modelBtn.text = "下载模型"
            modelProgress.visibility = View.GONE
        }
    }

    private fun startDownload() {
        downloading = true
        refreshModelStatus()
        modelProgress.progress = 0
        modelProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelManager.download(this@SettingsActivity) { pct ->
                        runOnUiThread {
                            modelProgress.progress = pct
                            modelStatus.text = "下载中 $pct%"
                        }
                    }
                }
                runOnUiThread { Toast.makeText(this@SettingsActivity, "模型下载完成", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                downloading = false
                runOnUiThread { refreshModelStatus() }
            }
        }
    }

    private fun formatSize(bytes: Long): String =
        String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}