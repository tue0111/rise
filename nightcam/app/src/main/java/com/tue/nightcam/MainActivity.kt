package com.tue.nightcam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tue.nightcam.core.Calc
import com.tue.nightcam.core.ConfigValidator
import com.tue.nightcam.core.Presets
import com.tue.nightcam.databinding.ActivityMainBinding
import com.tue.nightcam.record.ConfigStore
import com.tue.nightcam.settings.SettingsActivity
import android.os.StatFs
import android.os.Environment
import com.google.android.material.chip.Chip
import android.view.View
import android.view.ViewConfiguration
import com.tue.nightcam.preview.PreviewTransform
import com.tue.nightcam.probe.CapabilityProbe
import com.tue.nightcam.probe.toJson
import com.tue.nightcam.record.RecState
import com.tue.nightcam.record.RecStatus
import com.tue.nightcam.record.RecordingService
import com.tue.nightcam.record.SegmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    private var service: RecordingService? = null
    private var bound = false
    private var stateJob: Job? = null
    private var lastState = RecState()
    private var previewSurface: Surface? = null
    private var sensorOrientation = 90

    companion object {
        private const val KEY_ASKED_BATTERY = "asked_battery_exemption"
        private const val HOLD_TO_STOP_MS = 1_000L
    }

    private val timerTick = object : Runnable {
        override fun run() {
            renderTimer()
            handler.postDelayed(this, 1000L)
        }
    }

    /** Xin quyền lúc mở app: có quyền thì mở preview ngay. */
    private val startupPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (cameraGranted()) {
                attachPreviewIfReady()
            } else {
                binding.status.text = "Cần quyền camera để xem trước và quay."
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (cameraGranted()) {
                attachPreviewIfReady()
                startRecording()
            } else {
                binding.status.text = "Cần quyền camera để quay."
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as RecordingService.LocalBinder).service
            service = svc
            stateJob?.cancel()
            stateJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    svc.state.collect { render(it) }
                }
            }
            attachPreviewIfReady()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            stateJob?.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnRecord.setOnClickListener { onRecordClicked() }
        binding.btnRecord.setOnTouchListener { _, event -> onRecordTouch(event) }
        binding.btnInfo.setOnClickListener { showDeviceInfo() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        buildPresetChips()
        binding.btnAutoFocus.setOnClickListener { service?.setAutoFocus() }
        binding.btnInfinity.setOnClickListener { service?.setFixedFocus(0f) }
        binding.btnLock.setOnClickListener { service?.setLocked(!lastState.locked) }
        binding.btnEvMinus.setOnClickListener { service?.adjustEv(-1) }
        binding.btnEvPlus.setOnClickListener { service?.adjustEv(1) }
        binding.focusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) service?.setFocusFraction(progress / 100f)
            }

            override fun onStartTrackingTouch(seek: SeekBar) {}
            override fun onStopTrackingTouch(seek: SeekBar) {}
        })
        binding.previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                service?.tapFocus(event.x / view.width, event.y / view.height)
            }
            true
        }
        binding.previewView.surfaceTextureListener = previewListener
        render(RecState())
        if (savedInstanceState == null) {
            recoverLeftovers()
            val missing = requiredPermissions().filter { !isGranted(it) }
            if (missing.isNotEmpty()) startupPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private val previewListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            texture.setDefaultBufferSize(PreviewTransform.BUFFER_WIDTH, PreviewTransform.BUFFER_HEIGHT)
            previewSurface = Surface(texture)
            applyPreviewTransform()
            attachPreviewIfReady()
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            applyPreviewTransform()
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            service?.detachPreview()
            previewSurface?.release()
            previewSurface = null
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
    }

    /** Phục hồi file dở dang của lần chạy trước (app bị chết giữa chừng). */
    private fun recoverLeftovers() {
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { SegmentStore(contentResolver).recoverPending() }
            if (r.total > 0) {
                Snackbar.make(
                    binding.root,
                    "Phục hồi: ${r.published} đoạn giữ lại, ${r.broken} đoạn lỗi, ${r.removed} đoạn rỗng đã xóa.",
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindToService()
        attachPreviewIfReady()
    }

    override fun onResume() {
        super.onResume()
        updateCapacityText()
        highlightPreset()
    }

    /** Chip chọn nhanh preset. Chọn xong thì lưu thành cấu hình hiện tại. */
    private fun buildPresetChips() {
        Presets.ALL.forEach { preset ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = preset.label
                tag = preset.id
                isCheckable = true
                setOnClickListener {
                    ConfigStore(this@MainActivity).save(preset.config)
                    updateCapacityText()
                }
            }
            binding.presetGroup.addView(chip)
        }
    }

    private fun highlightPreset() {
        val current = ConfigStore(this).load()
        for (i in 0 until binding.presetGroup.childCount) {
            val chip = binding.presetGroup.getChildAt(i) as Chip
            val preset = Presets.byId(chip.tag as String)
            chip.isChecked = preset?.config == current
        }
    }

    /** Ước lượng dung lượng theo cấu hình hiện tại và chỗ trống thực tế. */
    private fun updateCapacityText() {
        val cfg = ConfigStore(this).load()
        val free = freeBytesForUi()
        val hours = Calc.hoursRemaining(free, cfg.reserveGb * 1_000_000_000L, cfg.totalBps)
        val perHour = "≈ %.1f GB/giờ".format(Calc.gbPerHour(cfg.totalBps / 1e6))
        binding.capacityText.text = if (hours != null) {
            "$perHour · còn quay được ~%.1f giờ".format(hours)
        } else {
            perHour
        }
    }

    @Suppress("DEPRECATION")
    private fun freeBytesForUi(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (_: Exception) {
        0L
    }

    /**
     * Khi đang quay: giữ nút Dừng 1 giây mới dừng, tránh chạm nhầm.
     * Khi chưa quay: để click xử lý như bình thường.
     */
    private fun onRecordTouch(event: MotionEvent): Boolean {
        if (!isActive()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> handler.postDelayed(stopHold, HOLD_TO_STOP_MS)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(stopHold)
        }
        return true
    }

    private val stopHold = Runnable { stopRecording() }

    private fun isActive(): Boolean =
        lastState.status == RecStatus.RECORDING || lastState.status == RecStatus.PREPARING

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    override fun onStop() {
        // Ẩn màn hình thì trả camera để tiết kiệm pin. Phiên quay đang chạy không bị ảnh hưởng.
        service?.detachPreview()
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
            service = null
            stateJob?.cancel()
        }
        handler.removeCallbacks(timerTick)
    }

    private fun bindToService() {
        if (!bound) {
            bound = bindService(Intent(this, RecordingService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun attachPreviewIfReady() {
        if (!cameraGranted()) return
        val svc = service ?: return
        val surface = previewSurface ?: return
        svc.attachPreview(surface)
    }

    private fun applyPreviewTransform() {
        PreviewTransform.apply(binding.previewView, sensorOrientation)
    }

    private fun onRecordClicked() {
        if (isActive()) {
            stopRecording()
            return
        }
        val missing = requiredPermissions().filter { !isGranted(it) }
        if (!cameraGranted()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            askBatteryExemptionOnce()
            startRecording()
        }
    }

    /** Nhắc tắt tối ưu pin đúng một lần, để máy không dừng app khi quay lâu. */
    private fun askBatteryExemptionOnce() {
        val prefs = getSharedPreferences("nightcam_ui", Context.MODE_PRIVATE)
        val pm = getSystemService(PowerManager::class.java)
        if (prefs.getBoolean(KEY_ASKED_BATTERY, false) || pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean(KEY_ASKED_BATTERY, true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("Tắt tối ưu pin")
            .setMessage("Để quay lâu không bị hệ thống dừng, hãy cho phép NightCam chạy không giới hạn pin.")
            .setPositiveButton("Mở cài đặt") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName")),
                )
            }
            .setNegativeButton("Để sau", null)
            .show()
    }

    private fun startRecording() {
        val intent = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_ROTATION_DEG, displayRotationDeg())
        ContextCompat.startForegroundService(this, intent)
        bindToService()
    }

    private fun render(st: RecState) {
        lastState = st
        val active = isActive()
        val recording = st.status == RecStatus.RECORDING
        binding.btnRecord.text = when {
            recording -> "Giữ 1 giây để dừng"
            active -> "Dừng"
            else -> "Quay"
        }
        binding.btnRecord.isEnabled = st.status != RecStatus.FINALIZING
        applyRecordingLayout(active)
        binding.status.text = statusText(st)
        binding.warnings.text = st.warnings.joinToString("\n") { "• $it" }
        binding.focusText.text = listOf(st.focusText, st.measureText)
            .filter { it.isNotEmpty() }
            .joinToString("  ·  ")
        binding.btnLock.text = if (st.locked) "Mở khóa sáng/màu" else "Khóa sáng/màu"
        binding.evText.text = st.evText
        binding.focusSeek.visibility = if (st.manualFocus) android.view.View.VISIBLE else android.view.View.GONE

        handler.removeCallbacks(timerTick)
        if (recording) handler.post(timerTick) else renderTimer()
        if (!active) updateCapacityText()
    }

    /**
     * Khi đang quay: nền đen, ẩn preview và điều khiển, chỉ còn đồng hồ và nút dừng.
     * Preview chỉ ẩn chứ không hủy surface, để khi dừng có thể xem trước lại ngay.
     */
    private fun applyRecordingLayout(active: Boolean) {
        val visible = if (active) View.GONE else View.VISIBLE
        binding.controlsPanel.visibility = visible
        binding.bottomRow.visibility = visible
        binding.previewView.visibility = if (active) View.INVISIBLE else View.VISIBLE
        binding.status.setTextColor(if (active) 0xFF888888.toInt() else 0xFFFFFFFF.toInt())
        binding.timer.setTextColor(if (active) 0xFF888888.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun renderTimer() {
        val st = lastState
        binding.timer.text = if (st.status == RecStatus.RECORDING) {
            Calc.formatDuration(System.currentTimeMillis() - st.startedAtMs)
        } else {
            "00:00:00"
        }
    }

    private fun statusText(st: RecState): String = when (st.status) {
        RecStatus.IDLE -> st.message.ifEmpty { "Sẵn sàng" }
        RecStatus.PREPARING -> "Đang chuẩn bị…"
        RecStatus.RECORDING -> "Đang quay · ${st.fileName}\n${st.healthText}\nCó thể tắt màn hình bằng nút nguồn."
        RecStatus.FINALIZING -> "Đang lưu file…"
        RecStatus.ERROR -> "Lỗi: ${st.message}"
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun cameraGranted(): Boolean = isGranted(Manifest.permission.CAMERA)

    /** Góc xoay màn hình theo độ (0, 90, 180, 270). */
    private fun displayRotationDeg(): Int {
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        return rotation * 90
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
        sensorOrientation = caps.sensorOrientation
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
