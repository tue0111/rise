package com.tue.nightcam

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tue.nightcam.core.ConfigValidator
import com.tue.nightcam.core.Presets
import com.tue.nightcam.databinding.ActivityMainBinding
import com.tue.nightcam.probe.CapabilityProbe
import com.tue.nightcam.probe.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.status.text = "NightCam ${BuildConfig.VERSION_NAME}"
        binding.btnInfo.setOnClickListener { showDeviceInfo() }
    }

    private fun showDeviceInfo() {
        binding.btnInfo.isEnabled = false
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { buildInfoText() }
            binding.btnInfo.isEnabled = true
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Thông tin máy")
                .setMessage(text)
                .setPositiveButton("Sao chép") { _, _ ->
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("NightCam", text))
                }
                .setNegativeButton("Đóng", null)
                .show()
        }
    }

    private fun buildInfoText(): String {
        val caps = CapabilityProbe.probe(this) ?: return "Không tìm thấy camera sau."
        val result = ConfigValidator.validate(Presets.NIGHT_FIXED.config, caps)
        return buildString {
            appendLine("Preset: ${Presets.NIGHT_FIXED.label}")
            if (result.warnings.isEmpty()) {
                appendLine("Cấu hình được máy hỗ trợ đầy đủ.")
            } else {
                result.warnings.forEach { appendLine("• $it") }
            }
            appendLine()
            append(caps.toJson().toString(2))
        }
    }
}
