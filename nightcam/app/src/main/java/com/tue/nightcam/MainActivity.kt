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

    private val timerTick = object : Runnable {
        override fun run() {
            renderTimer()
            handler.postDelayed(this, 1000L)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (cameraGranted()) {
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
        binding.btnInfo.setOnClickListener { showDeviceInfo() }
        binding.btnAutoFocus.setOnClickListener { service?.setAutoFocus() }
        binding.btnInfinity.setOnClickListener { service?.setFixedFocus(0f) }
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
        if (savedInstanceState == null) recoverLeftovers()
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
        val svc = service ?: return
        val surface = previewSurface ?: return
        svc.attachPreview(surface)
    }

    private fun applyPreviewTransform() {
        PreviewTransform.apply(binding.previewView, sensorOrientation)
    }

    private fun onRecordClicked() {
        if (lastState.status == RecStatus.RECORDING || lastState.status == RecStatus.PREPARING) {
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            return
        }
        val missing = requiredPermissions().filter { !isGranted(it) }
        if (!cameraGranted()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startRecording()
        }
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
        val active = st.status == RecStatus.RECORDING || st.status == RecStatus.PREPARING
        val recording = st.status == RecStatus.RECORDING
        binding.btnRecord.text = if (active) "Dừng" else "Quay"
        binding.btnRecord.isEnabled = st.status != RecStatus.FINALIZING
        binding.status.text = statusText(st)
        binding.warnings.text = st.warnings.joinToString("\n") { "• $it" }
        binding.focusText.text = st.focusText
        binding.evText.text = st.evText
        binding.focusSeek.visibility = if (st.manualFocus) android.view.View.VISIBLE else android.view.View.GONE

        handler.removeCallbacks(timerTick)
        if (recording) handler.post(timerTick) else renderTimer()
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
        RecStatus.RECORDING -> "Đang quay\n${st.fileName}"
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
