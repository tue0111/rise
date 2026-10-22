package com.tue.nightcam.record

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.BatteryManager
import android.os.Binder
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.MediaStore
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.tue.nightcam.R
import com.tue.nightcam.core.Calc
import com.tue.nightcam.core.ConfigValidator
import com.tue.nightcam.core.DeviceCaps
import com.tue.nightcam.core.FocusMode
import com.tue.nightcam.core.FpsRangePicker
import com.tue.nightcam.core.HealthRules
import com.tue.nightcam.core.RecordingConfig
import com.tue.nightcam.core.Segments
import com.tue.nightcam.log.SessionLog
import com.tue.nightcam.probe.CapabilityProbe
import com.tue.nightcam.probe.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Chủ sở hữu duy nhất của camera và recorder (docs/PLAN.md mục 4).
 * Lõi ghi hình dùng CameraX (Preview / VideoCapture bind vào lifecycle của service), giống XSCamera.
 * Khi chưa quay: chỉ bind Preview để xem trước. Khi quay: chỉ bind VideoCapture, nên tắt màn hình
 * hay ẩn Activity không làm đổi session. Mọi hàm chạy trên main thread, trừ callback đo từng frame.
 */
@OptIn(ExperimentalCamera2Interop::class)
class RecordingService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    /** Thiết lập cố định của một phiên quay. [rotation] là Surface.ROTATION_*. */
    private class Plan(
        val cfg: RecordingConfig,
        val rotation: Int,
        val stamp: String,
        val warnings: List<String>,
    ) {
        fun withConfig(next: RecordingConfig) = Plan(next, rotation, stamp, warnings)
    }

    private val binder = LocalBinder()

    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()

    private lateinit var mainHandler: Handler
    private lateinit var mainExecutor: Executor

    // CameraX
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var boundCfg: RecordingConfig? = null
    private var recording: Recording? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    // Điều khiển ảnh
    private var caps: DeviceCaps? = null
    private var liveCfg = RecordingConfig()
    private var focus: FocusMode = FocusMode.Continuous
    private var focusing = false
    private var evIndex = 0
    private var locked = false

    @Volatile
    private var lastDistance: Float? = null

    // Phiên quay
    private var plan: Plan? = null
    private var sessionActive = false
    private var stopReason: String? = null
    private var stopIsError = false
    private var restartRequested = false
    private var restartConfig: RecordingConfig? = null
    private var sessionStartedMs = 0L
    private var segmentIndex = 0
    private var currentName = ""
    private val gaps = mutableListOf<String>()
    private val restartTimes = mutableListOf<Long>()
    private var lastBytes = 0L
    private var lastGrowthMs = 0L
    private var log: SessionLog? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioEnabled = false
    private var thermalStatus = 0
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    // Đo FPS, chỉ đọc ghi trên thread callback của camera.
    private var measureStartNs = 0L
    private var measureFrames = 0
    private var expSumNs = 0L
    private var isoSum = 0L
    private var measureSamples = 0

    private val notifyTicker = object : Runnable {
        override fun run() {
            notifyForeground()
            mainHandler.postDelayed(this, NOTIFY_INTERVAL_MS)
        }
    }
    private val healthRunnable = Runnable { healthTick() }
    private val stopTimeout = Runnable {
        stopReason?.let { completeStop(it, stopIsError) }
    }

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(Looper.getMainLooper())
        mainExecutor = ContextCompat.getMainExecutor(this)
        val pm = getSystemService(PowerManager::class.java)
        val thermal = PowerManager.OnThermalStatusChangedListener { status -> thermalStatus = status }
        thermalListener = thermal
        pm.addThermalStatusListener(mainExecutor, thermal)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startSession(intent.getIntExtra(EXTRA_ROTATION_DEG, 0))
            ACTION_STOP -> finishSession(STOP_MESSAGE)
        }
        return START_NOT_STICKY
    }

    // ---- API cho Activity (main thread) ----

    fun attachPreview(provider: Preview.SurfaceProvider) {
        surfaceProvider = provider
        bindPreview()
    }

    fun detachPreview() {
        surfaceProvider = null
        if (!sessionActive) unbindCamera()
    }

    /** Chạm để lấy nét. Sau khi AF xong thì khóa khoảng cách nét hiện tại. */
    fun tapFocus(point: MeteringPoint) {
        val cam = camera ?: return
        focus = FocusMode.Continuous
        applyControls()
        focusing = true
        publishControls()
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            focusing = false
            val d = lastDistance
            focus = if (d != null) FocusMode.Locked(d) else FocusMode.Continuous
            applyControls()
            publishControls()
        }, mainExecutor)
    }

    fun setAutoFocus() {
        focus = FocusMode.Continuous
        applyControls()
        publishControls()
    }

    /** Khóa khoảng cách nét bằng diopter. 0 là vô cực. */
    fun setFixedFocus(diopters: Float) {
        focus = FocusMode.Locked(diopters)
        applyControls()
        publishControls()
    }

    /** Lấy nét tay theo tỷ lệ 0..1, từ vô cực tới khoảng cách gần nhất của máy. */
    fun setFocusFraction(fraction: Float) {
        val c = caps ?: return
        if (c.minFocusDiopters <= 0f) return
        setFixedFocus(fraction.coerceIn(0f, 1f) * c.minFocusDiopters)
    }

    fun adjustEv(delta: Int) {
        val range = caps?.evRange ?: return
        evIndex = (evIndex + delta).coerceIn(range)
        applyControls()
        publishControls()
    }

    /** Khóa hoặc mở khóa AE và AWB. */
    fun setLocked(on: Boolean) {
        locked = on
        applyControls()
        publishControls()
    }

    // ---- Camera ----

    private fun withProvider(block: (ProcessCameraProvider) -> Unit) {
        cameraProvider?.let {
            block(it)
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                cameraProvider = p
                block(p)
            } catch (e: Exception) {
                finishSession("Không khởi tạo được camera: ${e.message}", error = true)
            }
        }, mainExecutor)
    }

    private fun bindPreview() {
        if (sessionActive || surfaceProvider == null) return
        if (!hasPermission(Manifest.permission.CAMERA)) return
        withProvider { p ->
            val sp = surfaceProvider
            if (sessionActive || sp == null) return@withProvider
            try {
                refreshCaps(ConfigStore(this).load().copy(audio = false))
                val builder = Preview.Builder()
                Camera2Interop.Extender(builder).setSessionCaptureCallback(captureCallback)
                val pv = builder.build()
                pv.setSurfaceProvider(sp)
                p.unbindAll()
                videoCapture = null
                boundCfg = null
                preview = pv
                camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv)
                applyControls()
            } catch (e: Exception) {
                _state.value = _state.value.copy(message = "Không mở được xem trước: ${e.message}")
            }
        }
    }

    private fun unbindCamera() {
        cameraProvider?.unbindAll()
        camera = null
        preview = null
        videoCapture = null
        boundCfg = null
    }

    /** Bind VideoCapture (không có Preview) theo cấu hình của phiên. */
    private fun bindVideo(p: ProcessCameraProvider, plan: Plan): VideoCapture<Recorder> {
        val quality = when (plan.cfg.resolutionId) {
            RecordingConfig.RESOLUTION_2160P -> Quality.UHD
            RecordingConfig.RESOLUTION_720P -> Quality.HD
            else -> Quality.FHD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .setTargetVideoEncodingBitRate(plan.cfg.videoBps.toInt())
            .build()
        val builder = VideoCapture.Builder(recorder).setTargetRotation(plan.rotation)
        Camera2Interop.Extender(builder).setSessionCaptureCallback(captureCallback)
        val vc = builder.build()
        p.unbindAll()
        preview = null
        camera = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, vc)
        videoCapture = vc
        boundCfg = plan.cfg
        applyControls()
        return vc
    }

    /** Áp mọi thiết lập Camera2 qua một chỗ duy nhất (CAMERA2_NOTES mục 5). */
    private fun applyControls() {
        val cam = camera ?: return
        val c = caps ?: return
        val cfg = liveCfg
        val b = CaptureRequestOptions.Builder()
        FpsRangePicker.pick(c.fpsRanges, cfg.profileId, cfg.minFpsFloor)?.let {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.last))
        }
        b.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE,
            if (cfg.nrId == RecordingConfig.NR_HQ) {
                CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            } else {
                CameraMetadata.NOISE_REDUCTION_MODE_FAST
            },
        )
        b.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (cfg.eis && c.eisSupported) {
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            } else {
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            },
        )
        if (cfg.ois && c.oisSupported) {
            b.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
            )
        }
        antibandingFor(c)?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it) }
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
        b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked)
        (focus as? FocusMode.Locked)?.let {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it.diopters)
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        cam.cameraControl.setExposureCompensationIndex(evIndex)
    }

    private fun refreshCaps(requested: RecordingConfig): ConfigValidator.Result? {
        val c = CapabilityProbe.probe(this) ?: return null
        caps = c
        val result = ConfigValidator.validate(requested, c)
        liveCfg = result.config
        publishControls()
        return result
    }

    // ---- Vòng đời phiên quay ----

    private fun startSession(rotationDeg: Int) {
        val requested = ConfigStore(this).load()
        audioEnabled = requested.audio && hasPermission(Manifest.permission.RECORD_AUDIO)
        // Phải gọi startForeground ngay, kể cả khi bỏ qua vì phiên đang chạy.
        goForeground()
        if (sessionActive) return
        _state.value = _state.value.copy(status = RecStatus.PREPARING, message = "Đang chuẩn bị…", warnings = emptyList())
        beginRecording(if (audioEnabled) requested else requested.copy(audio = false), rotationDeg)
    }

    private fun beginRecording(requested: RecordingConfig, rotationDeg: Int) {
        try {
            val result = refreshCaps(requested) ?: return finishSession("Không tìm thấy camera sau.", error = true)
            val c = caps ?: return finishSession("Không tìm thấy camera sau.", error = true)
            var cfg = result.config
            val warnings = result.warnings.toMutableList()
            if (cfg.isHevc) {
                // CameraX 1.4 chưa cho chọn codec: ghi H.264 với bitrate bù.
                val mbps = Calc.avcFallbackMbps(cfg.videoMbps)
                    .coerceAtMost(((c.avcMaxBps ?: Int.MAX_VALUE) / 1_000_000).coerceAtLeast(1))
                cfg = cfg.copy(codecId = RecordingConfig.CODEC_AVC, videoMbps = mbps)
                warnings += "Bản này ghi H.264, ${cfg.videoMbps} Mbps."
            }
            liveCfg = cfg
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            plan = Plan(cfg, targetRotation(cfg.orientationId, rotationDeg), stamp, warnings)
            log = SessionLog.open(this, stamp).also { l ->
                l.line("thiết bị: ${c.toJson()}")
                l.line("yêu cầu: ${requested.toMap()}")
                l.line("thực tế: ${cfg.toMap()}")
                warnings.forEach { l.line("cảnh báo: $it") }
            }
            gaps.clear()
            restartTimes.clear()
            stopReason = null
            restartRequested = false
            restartConfig = null
            sessionActive = true
            sessionStartedMs = System.currentTimeMillis()
            SegmentStore.writing = true
            acquireWakeLock()
            segmentIndex = 1
            startSegment()
        } catch (e: Exception) {
            finishSession("Không bắt đầu được: ${e.message}", error = true)
        }
    }

    /** Bắt đầu ghi một đoạn. Đoạn tự kết thúc khi đủ thời lượng hoặc dung lượng. */
    @SuppressLint("MissingPermission")
    private fun startSegment() {
        if (!sessionActive || stopReason != null) return
        withProvider { prov ->
            val p = plan
            if (!sessionActive || stopReason != null || p == null) return@withProvider
            try {
                val existing = videoCapture
                val vc = if (existing != null && boundCfg == p.cfg) existing else bindVideo(prov, p)
                val name = Segments.fileName(p.stamp, segmentIndex)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, SegmentStore.RELATIVE_PATH)
                }
                val options = MediaStoreOutputOptions.Builder(contentResolver, SegmentStore.COLLECTION)
                    .setContentValues(values)
                    .setDurationLimitMillis(p.cfg.segmentMinutes * 60_000L)
                    .setFileSizeLimit(p.cfg.segmentBytes)
                    .build()
                var pending = vc.output.prepareRecording(this, options)
                if (p.cfg.audio && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    pending = pending.withAudioEnabled()
                }
                currentName = name
                log?.line("mở đoạn $name")
                recording = pending.start(mainExecutor) { event -> onRecordEvent(event) }
            } catch (e: Exception) {
                finishSession("Không bắt đầu được: ${e.message}", error = true)
            }
        }
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> onSegmentStarted()
            is VideoRecordEvent.Status -> {
                val bytes = event.recordingStats.numBytesRecorded
                if (bytes > lastBytes) {
                    lastBytes = bytes
                    lastGrowthMs = System.currentTimeMillis()
                }
            }
            is VideoRecordEvent.Finalize -> onSegmentFinalized(event)
        }
    }

    private fun onSegmentStarted() {
        wakeLock?.acquire(WAKE_LOCK_MS)
        lastBytes = 0L
        lastGrowthMs = System.currentTimeMillis()
        mainHandler.removeCallbacks(healthRunnable)
        mainHandler.postDelayed(healthRunnable, HEALTH_INTERVAL_MS)
        _state.value = _state.value.copy(
            status = RecStatus.RECORDING,
            startedAtMs = sessionStartedMs,
            segmentIndex = segmentIndex,
            fileName = currentName,
            warnings = plan?.warnings.orEmpty() + gaps,
            message = "",
        )
        mainHandler.removeCallbacks(notifyTicker)
        mainHandler.post(notifyTicker)
    }

    private fun onSegmentFinalized(event: VideoRecordEvent.Finalize) {
        recording = null
        val err = event.error
        log?.line("đóng đoạn $currentName: mã=$err ${event.cause?.message.orEmpty()}")

        stopReason?.let {
            completeStop(it, stopIsError)
            return
        }
        if (!sessionActive) return

        if (restartRequested) {
            restartRequested = false
            restartConfig?.let { next -> plan = plan?.withConfig(next) }
            restartConfig = null
            videoCapture = null
            boundCfg = null
            segmentIndex += 1
            mainHandler.postDelayed({ startSegment() }, RESTART_DELAY_MS)
            return
        }
        when (err) {
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> {
                segmentIndex += 1
                startSegment()
            }
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
                completeStop("Hết dung lượng", error = false)
            else -> {
                val now = System.currentTimeMillis()
                if (HealthRules.restartAllowed(restartTimes, now)) {
                    restartTimes += now
                    gaps += "Lỗi ghi (mã $err), đã mở lại từ đoạn ${segmentIndex + 1}"
                    videoCapture = null
                    boundCfg = null
                    segmentIndex += 1
                    mainHandler.postDelayed({ startSegment() }, RESTART_DELAY_MS)
                } else {
                    completeStop("Lỗi ghi (mã $err): ${event.cause?.message.orEmpty()}", error = true)
                }
            }
        }
    }

    /** Dừng đoạn hiện tại rồi mở đoạn mới (máy nóng, ghi bị đứng). Có giới hạn số lần mỗi giờ. */
    private fun requestRestart(reason: String, next: RecordingConfig?) {
        val now = System.currentTimeMillis()
        if (!HealthRules.restartAllowed(restartTimes, now)) {
            finishSession("Mở lại quá nhiều lần trong một giờ, đã dừng")
            return
        }
        restartTimes += now
        gaps += "$reason (từ đoạn ${segmentIndex + 1})"
        log?.line("mở lại: $reason")
        restartRequested = true
        restartConfig = next
        recording?.stop() ?: run {
            restartRequested = false
            next?.let { plan = plan?.withConfig(it) }
            videoCapture = null
            segmentIndex += 1
            startSegment()
        }
    }

    /**
     * Kết thúc phiên. Nếu đang ghi thì dừng recording và chờ Finalize (tối đa 5 giây)
     * rồi mới dọn dẹp, để file kịp đóng đúng.
     */
    private fun finishSession(reason: String, error: Boolean = false) {
        if (!sessionActive) {
            _state.value = _state.value.copy(status = if (error) RecStatus.ERROR else RecStatus.IDLE, message = reason)
            ServiceCompat.stopForeground(this, STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (stopReason != null) return
        stopReason = reason
        stopIsError = error
        log?.line(if (error) "lỗi, dừng: $reason" else "dừng: $reason")
        _state.value = _state.value.copy(status = RecStatus.FINALIZING, message = reason)
        val r = recording
        if (r != null) {
            r.stop()
            mainHandler.postDelayed(stopTimeout, STOP_TIMEOUT_MS)
        } else {
            completeStop(reason, error)
        }
    }

    private fun completeStop(reason: String, error: Boolean) {
        mainHandler.removeCallbacks(stopTimeout)
        mainHandler.removeCallbacks(healthRunnable)
        mainHandler.removeCallbacks(notifyTicker)
        recording?.close()
        recording = null
        sessionActive = false
        stopReason = null
        plan = null
        SegmentStore.writing = false
        unbindCamera()
        releaseWakeLock()
        var message = reason
        log?.let { l ->
            if (!l.close(contentResolver)) message = "$message (không lưu được log)"
        }
        log = null
        _state.value = _state.value.copy(
            status = if (error) RecStatus.ERROR else RecStatus.IDLE,
            message = message,
            startedAtMs = 0L,
            fileName = "",
            warnings = emptyList(),
            healthText = "",
        )
        publishControls()
        ServiceCompat.stopForeground(this, STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (!error && surfaceProvider != null) bindPreview()
    }

    // ---- Sức khỏe phiên ----

    /** Pin, nhiệt, dung lượng và file đứng yên, kiểm tra mỗi 15 giây. */
    private fun healthTick() {
        if (!sessionActive || stopReason != null) return
        val p = plan ?: return
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val free = freeBytes()
        val reserve = p.cfg.reserveGb * 1_000_000_000L
        val hours = Calc.hoursRemaining(free, reserve, p.cfg.totalBps)
        val healthText = "Pin $percent % · %.0f°C · còn %.1f GB%s".format(
            tempC,
            free / 1e9,
            hours?.let { " (≈ %.1f giờ)".format(it) }.orEmpty(),
        )
        _state.value = _state.value.copy(healthText = healthText)
        log?.line("sức khỏe: $healthText, nhiệt=$thermalStatus, cắm sạc=$plugged")

        val stop = listOf(
            HealthRules.storage(free, reserve),
            HealthRules.battery(percent, plugged),
            HealthRules.thermal(thermalStatus, p.cfg.thermalDowngrade),
        ).firstOrNull { it is HealthRules.Decision.Stop } as HealthRules.Decision.Stop?
        if (stop != null) {
            finishSession(stop.reason)
            return
        }
        val downgrade = HealthRules.thermal(thermalStatus, p.cfg.thermalDowngrade) as? HealthRules.Decision.Downgrade
        if (downgrade != null) {
            val next = HealthRules.downgrade(p.cfg)
            if (next != p.cfg) {
                requestRestart(downgrade.reason, next)
                return
            }
        }
        val now = System.currentTimeMillis()
        if (recording != null && HealthRules.stalled(lastBytes, lastBytes, (now - lastGrowthMs) / 1000)) {
            requestRestart("Ghi bị đứng, mở lại đoạn", null)
            return
        }
        mainHandler.postDelayed(healthRunnable, HEALTH_INTERVAL_MS)
    }

    @Suppress("DEPRECATION")
    private fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (_: Exception) {
        0L
    }

    // ---- Đo từng frame (thread camera của CameraX) ----

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastDistance = it }
            measure(result)
        }
    }

    private fun measure(result: CaptureResult) {
        val now = System.nanoTime()
        if (measureStartNs == 0L) measureStartNs = now
        measureFrames++
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
            expSumNs += it
            measureSamples++
        }
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { isoSum += it }
        val elapsed = now - measureStartNs
        if (elapsed < MEASURE_WINDOW_NS) return
        val fps = measureFrames * 1e9 / elapsed
        val expNs = if (measureSamples > 0) expSumNs / measureSamples else 0L
        val iso = if (measureSamples > 0) isoSum / measureSamples else 0L
        val shutter = if (expNs > 0) "1/${(1e9 / expNs).toInt()} s" else "— s"
        val text = "%.1f fps · %s · ISO %d".format(fps, shutter, iso)
        measureStartNs = 0L
        measureFrames = 0
        expSumNs = 0L
        isoSum = 0L
        measureSamples = 0
        mainHandler.post {
            _state.value = _state.value.copy(measureText = text)
            log?.line("đo: $text")
        }
    }

    // ---- Hiển thị ----

    private fun publishControls() {
        val c = caps
        val evText = if (c == null) "EV 0" else "EV %+.1f".format(evIndex * c.evStep).replace('-', '−')
        _state.value = _state.value.copy(
            focusText = focusLabel(c),
            evText = evText,
            manualFocus = c != null && c.minFocusDiopters > 0f,
            locked = locked,
        )
    }

    private fun focusLabel(c: DeviceCaps?): String = when {
        focusing -> "Đang lấy nét…"
        else -> when (val f = focus) {
            is FocusMode.Locked -> when {
                f.diopters <= 0f -> "Khóa nét: vô cực"
                c != null && c.focusCalibrated -> "Khóa nét ≈ %.2f m".format(1f / f.diopters)
                else -> "Đã khóa nét"
            }
            else -> "Lấy nét: tự động"
        }
    }

    private fun antibandingFor(caps: DeviceCaps): Int? = when {
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ in caps.antibandingModes ->
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO in caps.antibandingModes ->
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        else -> null
    }

    /** Hướng video theo lựa chọn, đổi sang Surface.ROTATION_* (0..3) cho CameraX. */
    private fun targetRotation(mode: String, displayDeg: Int): Int {
        val rot = Calc.roundToRightAngle(displayDeg)
        val chosen = when (mode) {
            RecordingConfig.ORIENTATION_LANDSCAPE -> if (rot == 90 || rot == 270) rot else 90
            RecordingConfig.ORIENTATION_PORTRAIT -> if (rot == 0 || rot == 180) rot else 0
            else -> rot
        }
        return chosen / 90
    }

    // ---- Thông báo và tài nguyên ----

    private fun goForeground() {
        ensureChannel()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            (if (audioEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun notifyForeground() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val st = _state.value
        val text = if (st.status == RecStatus.RECORDING) {
            val elapsed = Calc.formatDuration(System.currentTimeMillis() - st.startedAtMs)
            "$elapsed · đoạn ${st.segmentIndex}"
        } else {
            st.message.ifEmpty { "Đang chuẩn bị…" }
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name) + " · đang quay")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "Dừng", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NightCam:recording").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun hasPermission(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        mainHandler.removeCallbacksAndMessages(null)
        recording?.stop()
        recording = null
        SegmentStore.writing = false
        releaseWakeLock()
        log?.close(contentResolver)
        log = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.tue.nightcam.action.START"
        const val ACTION_STOP = "com.tue.nightcam.action.STOP"
        const val EXTRA_ROTATION_DEG = "rotation_deg"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFY_INTERVAL_MS = 10_000L
        private const val WAKE_LOCK_MS = 12 * 60 * 60 * 1000L
        private const val RESTART_DELAY_MS = 1_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val HEALTH_INTERVAL_MS = 15_000L
        private const val MEASURE_WINDOW_NS = 10_000_000_000L
        private const val STOP_MESSAGE = "Đã dừng"
    }
}
