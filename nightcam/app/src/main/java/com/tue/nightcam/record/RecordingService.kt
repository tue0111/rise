package com.tue.nightcam.record

import android.Manifest
import android.app.Notification
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.system.Os
import java.util.concurrent.Executor
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.MediaRecorder
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Range
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.tue.nightcam.R
import com.tue.nightcam.core.Calc
import com.tue.nightcam.core.ConfigValidator
import com.tue.nightcam.core.DeviceCaps
import com.tue.nightcam.core.FocusMode
import com.tue.nightcam.core.FocusRules
import com.tue.nightcam.core.FpsRangePicker
import com.tue.nightcam.core.HealthRules
import com.tue.nightcam.core.RecordingConfig
import com.tue.nightcam.log.SessionLog
import com.tue.nightcam.probe.toJson
import com.tue.nightcam.probe.CapabilityProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chủ sở hữu duy nhất của camera và recorder (docs/PLAN.md mục 4).
 * Activity chỉ điều khiển và hiển thị. Camera có thể mở để xem trước khi chưa quay;
 * đóng Activity hay tắt màn hình không dừng phiên quay đang chạy.
 * Mọi thao tác camera và recorder chạy trên thread nc-camera.
 */
class RecordingService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    /** Thiết lập cố định của một phiên quay, tính khi bắt đầu. */
    private class Plan(
        val cfg: RecordingConfig,
        val cameraId: String,
        val hint: Int,
        val stamp: String,
        val warnings: List<String>,
    ) {
        fun withConfig(next: RecordingConfig) = Plan(next, cameraId, hint, stamp, warnings)
    }

    private val binder = LocalBinder()

    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()

    private lateinit var camThread: HandlerThread
    private lateinit var camHandler: Handler
    private lateinit var mainHandler: Handler
    private lateinit var camera: CameraController
    private lateinit var recorder: RecorderController
    private lateinit var segments: SegmentStore

    // Các biến dưới đây chỉ đọc và ghi trên thread nc-camera.
    private var caps: DeviceCaps? = null
    private var liveCfg = RecordingConfig()
    private var previewSurface: Surface? = null
    private var focus: FocusMode = FocusMode.Continuous
    private var evIndex = 0
    private var locked = false

    // Giám sát sức khỏe phiên.
    private var thermalStatus = 0
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var cameraRetries = 0
    private val restartTimes = mutableListOf<Long>()
    private var lastSize = 0L
    private var lastGrowthMs = 0L
    private val healthRunnable = Runnable { healthTick() }

    // Số liệu đo FPS, cửa sổ 10 giây.
    private var measureStartNs = 0L
    private var measureFrames = 0
    private var expSumNs = 0L
    private var isoSum = 0L
    private var measureSamples = 0

    private var plan: Plan? = null
    private var log: SessionLog? = null
    private var sessionActive = false
    private var sessionStartedMs = 0L
    private var segmentIndex = 0
    private var currentSegment: SegmentStore.Segment? = null
    private var nextSegment: SegmentStore.Segment? = null
    private val gaps = mutableListOf<String>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioEnabled = false

    private val notifyTicker = object : Runnable {
        override fun run() {
            notifyForeground()
            mainHandler.postDelayed(this, NOTIFY_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(Looper.getMainLooper())
        camThread = HandlerThread("nc-camera").apply { start() }
        camHandler = Handler(camThread.looper)
        camera = CameraController(getSystemService(CameraManager::class.java), camHandler).apply {
            onFailure = { msg -> onCameraFailure(msg) }
            onFrame = { result -> onFrame(result) }
        }
        recorder = RecorderController(applicationContext)
        segments = SegmentStore(contentResolver)
        val pm = getSystemService(PowerManager::class.java)
        val thermal = PowerManager.OnThermalStatusChangedListener { status -> camHandler.post { thermalStatus = status } }
        thermalListener = thermal
        pm.addThermalStatusListener(Executor { camHandler.post(it) }, thermal)
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

    // ---- API cho Activity (gọi từ main thread, việc thật chạy trên nc-camera) ----

    fun attachPreview(surface: Surface) = camHandler.post {
        previewSurface = surface
        if (!sessionActive) openPreview()
    }

    fun detachPreview() = camHandler.post {
        previewSurface = null
        if (!sessionActive) camera.close()
    }

    /** Chạm để lấy nét tại (nx, ny) trong khung preview dọc, giá trị 0..1. */
    fun tapFocus(nx: Float, ny: Float) = camHandler.post {
        val c = caps ?: return@post
        if (!camera.isOpen) return@post
        val (sx, sy) = Calc.touchToSensor(nx, ny, c.sensorOrientation)
        val box = FocusRules.tapBox(sx, sy, c.activeWidth, c.activeHeight)
        focus = FocusMode.Tap(box, System.currentTimeMillis() + FocusRules.TAP_TIMEOUT_MS)
        camera.updateRequest(::configure)
        camera.triggerAf(::configure)
        publishControls()
    }

    fun setAutoFocus() = camHandler.post {
        focus = FocusMode.Continuous
        camera.updateRequest(::configure)
        publishControls()
    }

    /** Khóa khoảng cách nét bằng diopter. 0 là vô cực. */
    fun setFixedFocus(diopters: Float) = camHandler.post {
        focus = FocusMode.Locked(diopters)
        camera.updateRequest(::configure)
        publishControls()
    }

    /** Khóa hoặc mở khóa AE và AWB, dùng cho cảnh cố định khi sáng và màu đã ổn định. */
    fun setLocked(on: Boolean) = camHandler.post {
        locked = on
        camera.updateRequest(::configure)
        publishControls()
    }

    fun adjustEv(delta: Int) = camHandler.post {
        val range = caps?.evRange ?: return@post
        evIndex = (evIndex + delta).coerceIn(range)
        camera.updateRequest(::configure)
        publishControls()
    }

    /** Lấy nét tay theo tỷ lệ 0..1 trên thang từ vô cực tới khoảng cách gần nhất của máy. */
    fun setFocusFraction(fraction: Float) = camHandler.post {
        val c = caps ?: return@post
        if (c.minFocusDiopters <= 0f) return@post
        focus = FocusMode.Locked(fraction.coerceIn(0f, 1f) * c.minFocusDiopters)
        camera.updateRequest(::configure)
        publishControls()
    }

    // ---- Vòng đời phiên quay ----

    private fun startSession(rotationDeg: Int) {
        val requested = ConfigStore(this).load()
        audioEnabled = requested.audio && hasPermission(Manifest.permission.RECORD_AUDIO)
        // Phải gọi startForeground ngay, kể cả khi bỏ qua vì phiên đang chạy.
        goForeground()

        val status = _state.value.status
        if (status == RecStatus.PREPARING || status == RecStatus.RECORDING || status == RecStatus.FINALIZING) {
            return
        }
        _state.value = RecState(status = RecStatus.PREPARING, message = "Đang chuẩn bị…")
        camHandler.post { beginRecording(requested, rotationDeg) }
    }

    private fun beginRecording(requested: RecordingConfig, rotationDeg: Int) {
        try {
            val result = validateAndProbe(if (audioEnabled) requested else requested.copy(audio = false))
                ?: return failWith("Không tìm thấy camera sau.")
            val c = caps ?: return failWith("Không tìm thấy camera sau.")
            plan = Plan(
                cfg = result.config,
                cameraId = c.cameraId,
                hint = Calc.orientationFor(result.config.orientationId, c.sensorOrientation, rotationDeg),
                stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()),
                warnings = result.warnings,
            )
            gaps.clear()
            log = SessionLog.open(this, plan?.stamp.orEmpty()).also { l ->
                l.line("thiết bị: ${c.toJson()}")
                l.line("yêu cầu: ${requested.toMap()}")
                l.line("thực tế: ${result.config.toMap()}")
                result.warnings.forEach { l.line("cảnh báo: $it") }
            }
            sessionActive = true
            sessionStartedMs = System.currentTimeMillis()
            SegmentStore.writing = true
            acquireWakeLock()
            segmentIndex = 1
            startSegment()
        } catch (e: Exception) {
            failWith("Không bắt đầu được: ${e.message}")
        }
    }

    /** Mở một đoạn mới rồi bắt đầu ghi. Chạy trên nc-camera. */
    private fun startSegment() {
        val p = plan ?: return
        if (!sessionActive) return
        try {
            val segment = segments.create(p.stamp, segmentIndex)
            currentSegment = segment
            log?.line("mở đoạn ${segment.name}")
            val surface = recorder.prepare(
                config = p.cfg,
                output = segment.pfd.fileDescriptor,
                orientationHint = p.hint,
                maxBytes = p.cfg.segmentBytes,
                onInfo = { what -> onRecorderInfo(what) },
                onError = { what -> failWith("Lỗi recorder (mã $what)") },
            )
            if (camera.isOpen) {
                beginSessionOn(surface, segment)
            } else {
                camera.open(p.cameraId) {
                    // Người dùng đã dừng trong lúc camera đang mở: đóng camera ngay.
                    if (!sessionActive) {
                        camera.close()
                        return@open
                    }
                    beginSessionOn(surface, segment)
                }
            }
        } catch (e: Exception) {
            failWith("Không bắt đầu được: ${e.message}")
        }
    }

    private fun beginSessionOn(surface: Surface, segment: SegmentStore.Segment) {
        try {
            camera.startSession(
                targets = listOf(surface),
                template = CameraDevice.TEMPLATE_RECORD,
                configure = ::configure,
            ) { onSegmentStarted(segment) }
        } catch (e: Exception) {
            failWith("Không bắt đầu được: ${e.message}")
        }
    }

    /** Chạy trên nc-camera, sau khi session đã có repeating request. */
    private fun onSegmentStarted(segment: SegmentStore.Segment) {
        try {
            recorder.start()
        } catch (e: Exception) {
            failWith("Recorder không chạy: ${e.message}")
            return
        }
        cameraRetries = 0
        lastSize = 0L
        lastGrowthMs = System.currentTimeMillis()
        camHandler.removeCallbacks(healthRunnable)
        camHandler.postDelayed(healthRunnable, HEALTH_INTERVAL_MS)
        _state.value = _state.value.copy(
            status = RecStatus.RECORDING,
            startedAtMs = sessionStartedMs,
            segmentIndex = segmentIndex,
            fileName = segment.name,
            warnings = plan?.warnings.orEmpty() + gaps,
            message = "",
        )
        mainHandler.removeCallbacks(notifyTicker)
        mainHandler.post(notifyTicker)
        notifyForeground()
    }

    /** Sự kiện của MediaRecorder. Chạy trên nc-camera. */
    private fun onRecorderInfo(what: Int) {
        when (what) {
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> prepareNextSegment()
            MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> rotateToNextSegment()
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> restartWithBudget("Đủ dung lượng đoạn")
        }
    }

    /** Sắp đầy đoạn: tạo file kế tiếp và đặt cho recorder. Nếu lỗi thì 801 sẽ xử lý sau. */
    private fun prepareNextSegment() {
        val p = plan ?: return
        if (nextSegment != null) return
        val next = try {
            segments.create(p.stamp, segmentIndex + 1)
        } catch (_: Exception) {
            return
        }
        try {
            recorder.setNextOutput(next.pfd.fileDescriptor)
            nextSegment = next
        } catch (_: Exception) {
            segments.finalize(next)
        }
    }

    /** Recorder đã chuyển sang file kế tiếp (803). Hoàn tất đoạn cũ. */
    private fun rotateToNextSegment() {
        val next = nextSegment ?: return
        currentSegment?.let { segments.finalize(it) }
        currentSegment = next
        nextSegment = null
        segmentIndex += 1
        wakeLock?.acquire(WAKE_LOCK_MS)
        log?.line("chuyển sang đoạn ${next.name}")
        _state.value = _state.value.copy(segmentIndex = segmentIndex, fileName = next.name)
        notifyForeground()
    }

    /**
     * Recorder đã dừng vì đủ dung lượng và không có file kế tiếp (801). Mở lại đoạn mới.
     * Mỗi lần mở lại đều được tính vào giới hạn 3 lần mỗi giờ.
     */
    private fun restartWithBudget(reason: String, next: RecordingConfig? = null) {
        if (!sessionActive) return
        val now = System.currentTimeMillis()
        if (!HealthRules.restartAllowed(restartTimes, now)) {
            finishSession("Mở lại quá nhiều lần trong một giờ, đã dừng")
            return
        }
        restartTimes += now
        restartSegment(reason, next)
    }

    private fun restartSegment(reason: String, next: RecordingConfig? = null) {
        val p = plan ?: return
        tearDownPipeline()
        if (next != null) plan = p.withConfig(next)
        gaps += "$reason (từ đoạn ${segmentIndex + 1})"
        segmentIndex += 1
        camHandler.postDelayed({ startSegment() }, RESTART_DELAY_MS)
    }

    /** Dừng recorder, đóng camera và hoàn tất các file đang mở. Chạy trên nc-camera. */
    private fun tearDownPipeline() {
        nextSegment?.let { segments.finalize(it) }
        nextSegment = null
        recorder.stop()
        recorder.release()
        camera.close()
        currentSegment?.let { segments.finalize(it) }
        currentSegment = null
    }

    /** Camera lỗi giữa phiên: thử mở lại tối đa 2 lần, cách nhau 2 giây. */
    private fun onCameraFailure(msg: String) {
        if (sessionActive && cameraRetries < MAX_CAMERA_RETRIES) {
            cameraRetries++
            gaps += "Camera lỗi ($msg), đã mở lại"
            tearDownPipeline()
            segmentIndex += 1
            camHandler.postDelayed({ startSegment() }, CAMERA_RETRY_DELAY_MS)
        } else {
            finishSession(msg, error = true)
        }
    }

    /** Kiểm tra pin, nhiệt, dung lượng và file đứng yên. Chạy trên nc-camera mỗi 15 giây. */
    private fun healthTick() {
        if (!sessionActive) return
        val p = plan ?: return
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val free = freeBytes()
        val hours = Calc.hoursRemaining(free, p.cfg.reserveGb * 1_000_000_000L, p.cfg.totalBps)
        val healthText = "Pin $percent % · %.0f°C · còn %.1f GB%s".format(
            tempC,
            free / 1e9,
            hours?.let { " (≈ %.1f giờ)".format(it) }.orEmpty(),
        )
        _state.value = _state.value.copy(healthText = healthText)
        log?.line("sức khỏe: $healthText, nhiệt=$thermalStatus, cắm sạc=$plugged")

        val stop = listOf(
            HealthRules.storage(free, p.cfg.reserveGb * 1_000_000_000L),
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
                restartWithBudget(downgrade.reason, next)
                return
            }
        }

        val size = currentSegment?.let { Os.fstat(it.pfd.fileDescriptor).st_size } ?: 0L
        val now = System.currentTimeMillis()
        if (size > lastSize) {
            lastSize = size
            lastGrowthMs = now
        } else if (HealthRules.stalled(lastSize, size, (now - lastGrowthMs) / 1000)) {
            restartWithBudget("Ghi bị đứng, mở lại đoạn")
            return
        }
        camHandler.postDelayed(healthRunnable, HEALTH_INTERVAL_MS)
    }

    @Suppress("DEPRECATION")
    private fun freeBytes(): Long = try {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    } catch (_: Exception) {
        0L
    }

    private fun failWith(message: String) = finishSession(message, error = true)

    /**
     * Kết thúc phiên. Gọi được từ mọi thread. Dọn dẹp chạy trên nc-camera:
     * dừng recorder → đóng camera → hoàn tất file → nhả wake lock → dừng service.
     * Sau khi dừng bình thường, nếu Activity vẫn hiện thì mở lại preview.
     */
    private fun finishSession(reason: String, error: Boolean = false) {
        camHandler.post {
            camHandler.removeCallbacks(healthRunnable)
            var message = reason
            log?.line(if (error) "lỗi, dừng: $reason" else "dừng: $reason")
            if (sessionActive) {
                sessionActive = false
                SegmentStore.writing = false
                _state.value = _state.value.copy(status = RecStatus.FINALIZING, message = reason)
                val stopped = recorder.stop()
                camera.close()
                recorder.release()
                tearDownPipeline()
                plan = null
                releaseWakeLock()
                if (!stopped) message = "$reason (đoạn cuối có thể không đọc được)"
            }
            _state.value = _state.value.copy(
                status = if (error) RecStatus.ERROR else RecStatus.IDLE,
                message = message,
                startedAtMs = 0L,
                fileName = "",
                warnings = emptyList(),
                healthText = "",
            )
            publishControls()
            mainHandler.removeCallbacks(notifyTicker)
            log?.let { l ->
                val exported = l.close(contentResolver)
                log = null
                if (!exported) message = "$message (không lưu được log)"
            }
            _state.value = _state.value.copy(message = message)
            if (!error && previewSurface != null) openPreview()
            mainHandler.post {
                ServiceCompat.stopForeground(this, STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ---- Xem trước và cấu hình request ----

    private fun openPreview() {
        if (previewSurface == null) return
        val requested = ConfigStore(this).load().copy(audio = false)
        val result = validateAndProbe(requested)
        if (result == null) {
            _state.value = _state.value.copy(message = "Không tìm thấy camera sau.")
            return
        }
        val cameraId = caps?.cameraId ?: return
        camera.open(cameraId) {
            val surface = previewSurface
            if (surface == null) {
                camera.close()
                return@open
            }
            // Đang quay thì phiên quay tự tạo session của nó.
            if (sessionActive) return@open
            camera.startSession(
                targets = listOf(surface),
                template = CameraDevice.TEMPLATE_PREVIEW,
                configure = ::configure,
            ) {}
        }
    }

    /** Đưa nhãn lấy nét và EV ra trạng thái để UI hiển thị. Chạy trên nc-camera. */
    private fun publishControls() {
        val c = caps
        val evText = if (c == null) {
            "EV 0"
        } else {
            "EV %+.1f".format(evIndex * c.evStep).replace('-', '−')
        }
        _state.value = _state.value.copy(
            focusText = focusLabel(c),
            evText = evText,
            manualFocus = c != null && c.minFocusDiopters > 0f,
            locked = locked,
        )
    }

    private fun focusLabel(c: DeviceCaps?): String = when (val f = focus) {
        FocusMode.Continuous -> "Lấy nét: tự động"
        is FocusMode.Tap -> "Đang lấy nét…"
        is FocusMode.Locked -> when {
            f.diopters <= 0f -> "Khóa nét: vô cực"
            c != null && c.focusCalibrated -> "Khóa nét ≈ %.2f m".format(1f / f.diopters)
            else -> "Đã khóa nét"
        }
    }

    /** Chạy trên nc-camera. Đọc khả năng máy và cấu hình đã kiểm tra. */
    private fun validateAndProbe(requested: RecordingConfig): ConfigValidator.Result? {
        val c = CapabilityProbe.probe(this) ?: return null
        caps = c
        val result = ConfigValidator.validate(requested, c)
        liveCfg = result.config
        publishControls()
        return result
    }

    /** Dựng request. Mọi thiết lập camera đi qua đây (xem CAMERA2_NOTES mục 5). */
    private fun configure(b: CaptureRequest.Builder) {
        val c = caps ?: return
        val cfg = liveCfg
        FpsRangePicker.pick(c.fpsRanges, cfg.profileId, cfg.minFpsFloor)
            ?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.first, it.last)) }
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        b.set(CaptureRequest.CONTROL_AE_LOCK, locked)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, locked)
        b.set(
            CaptureRequest.NOISE_REDUCTION_MODE,
            if (cfg.nrId == RecordingConfig.NR_HQ) {
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            } else {
                CaptureRequest.NOISE_REDUCTION_MODE_FAST
            },
        )
        b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
        b.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (cfg.eis && c.eisSupported) {
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            } else {
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            },
        )
        if (cfg.ois && c.oisSupported) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        antibandingFor(c)?.let { b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it) }

        when (val f = focus) {
            FocusMode.Continuous -> b.set(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            )
            is FocusMode.Tap -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                val region = MeteringRectangle(f.box.left, f.box.top, f.box.width, f.box.height, MeteringRectangle.METERING_WEIGHT_MAX)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            }
            is FocusMode.Locked -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, f.diopters)
            }
        }
    }

    /** Kết quả từng frame, dùng để khóa nét sau khi chạm. Chạy trên nc-camera. */
    private fun onFrame(result: CaptureResult) {
        measure(result)
        val current = focus as? FocusMode.Tap ?: return
        val afState = result.get(CaptureResult.CONTROL_AF_STATE)
        val locked = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
        val next = FocusRules.afterFrame(
            current,
            locked,
            result.get(CaptureResult.LENS_FOCUS_DISTANCE),
            System.currentTimeMillis(),
        )
        if (next !== current) {
            focus = next
            camera.updateRequest(::configure)
            publishControls()
        }
    }

    /** Đo FPS thật và trung bình màn trập, ISO mỗi 10 giây. Chạy trên nc-camera. */
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
        _state.value = _state.value.copy(measureText = "%.1f fps · %s · ISO %d".format(fps, shutter, iso))
        log?.line("đo: %.2f fps, $shutter, ISO $iso".format(fps))
        measureStartNs = 0L
        measureFrames = 0
        expSumNs = 0L
        isoSum = 0L
        measureSamples = 0
    }

    private fun antibandingFor(caps: DeviceCaps): Int? = when {
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ in caps.antibandingModes ->
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO in caps.antibandingModes ->
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        else -> null
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
        mainHandler.removeCallbacks(notifyTicker)
        thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        finishSession("Dịch vụ đã dừng")
        camThread.quitSafely()
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
        private const val MEASURE_WINDOW_NS = 10_000_000_000L
        private const val HEALTH_INTERVAL_MS = 15_000L
        private const val CAMERA_RETRY_DELAY_MS = 2_000L
        private const val MAX_CAMERA_RETRIES = 2
        private const val STOP_MESSAGE = "Đã dừng"
    }
}
