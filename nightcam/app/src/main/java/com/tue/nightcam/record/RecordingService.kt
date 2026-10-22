package com.tue.nightcam.record

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Range
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.tue.nightcam.R
import com.tue.nightcam.core.Calc
import com.tue.nightcam.core.ConfigValidator
import com.tue.nightcam.core.DeviceCaps
import com.tue.nightcam.core.FpsRangePicker
import com.tue.nightcam.core.RecordingConfig
import com.tue.nightcam.probe.CapabilityProbe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Chủ sở hữu duy nhất của camera và recorder trong một phiên quay (docs/PLAN.md mục 4).
 * Activity chỉ điều khiển và hiển thị. Đóng Activity hay tắt màn hình không dừng phiên.
 * Mọi thao tác camera và recorder chạy trên thread nc-camera.
 */
class RecordingService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    /** Thiết lập cố định cho cả phiên, tính một lần khi bắt đầu. */
    private class Plan(
        val cfg: RecordingConfig,
        val cameraId: String,
        val hint: Int,
        val fps: Range<Int>?,
        val nrMode: Int,
        val ois: Boolean,
        val antibanding: Int?,
        val stamp: String,
        val warnings: List<String>,
    )

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
    private var plan: Plan? = null
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
            onFailure = { msg -> finishSession(msg, error = true) }
        }
        recorder = RecorderController(applicationContext)
        segments = SegmentStore(contentResolver)
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
            val caps = CapabilityProbe.probe(this) ?: return failWith("Không tìm thấy camera sau.")
            val result = ConfigValidator.validate(
                if (audioEnabled) requested else requested.copy(audio = false),
                caps,
            )
            val cfg = result.config
            plan = Plan(
                cfg = cfg,
                cameraId = caps.cameraId,
                hint = Calc.orientationFor(cfg.orientationId, caps.sensorOrientation, rotationDeg),
                fps = FpsRangePicker.pick(caps.fpsRanges, cfg.profileId, cfg.minFpsFloor)
                    ?.let { Range(it.first, it.last) },
                nrMode = if (cfg.nrId == RecordingConfig.NR_HQ) {
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                } else {
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                },
                ois = cfg.ois && caps.oisSupported,
                antibanding = antibandingFor(caps),
                stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()),
                warnings = result.warnings,
            )
            gaps.clear()
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

    /** Mở một đoạn mới: tạo file, chuẩn bị recorder, mở camera rồi bắt đầu ghi. Chạy trên nc-camera. */
    private fun startSegment() {
        val p = plan ?: return
        if (!sessionActive) return
        try {
            val segment = segments.create(p.stamp, segmentIndex)
            currentSegment = segment
            val surface = recorder.prepare(
                config = p.cfg,
                output = segment.pfd.fileDescriptor,
                orientationHint = p.hint,
                maxBytes = p.cfg.segmentBytes,
                onInfo = { what -> onRecorderInfo(what) },
                onError = { what -> failWith("Lỗi recorder (mã $what)") },
            )
            camera.open(p.cameraId) {
                // Người dùng đã dừng trong lúc camera đang mở: đóng camera ngay.
                if (!sessionActive) {
                    camera.close()
                    return@open
                }
                try {
                    camera.startRecording(
                        surface = surface,
                        fpsRange = p.fps,
                        nrMode = p.nrMode,
                        ois = p.ois,
                        antibandingMode = p.antibanding,
                    ) {
                        onSegmentStarted(segment)
                    }
                } catch (e: Exception) {
                    failWith("Không bắt đầu được: ${e.message}")
                }
            }
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
        _state.value = RecState(
            status = RecStatus.RECORDING,
            startedAtMs = sessionStartedMs,
            segmentIndex = segmentIndex,
            fileName = segment.name,
            warnings = plan?.warnings.orEmpty() + gaps,
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
            MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> restartSegment()
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
        _state.value = _state.value.copy(segmentIndex = segmentIndex, fileName = next.name)
        notifyForeground()
    }

    /**
     * Recorder đã dừng vì đủ dung lượng và không có file kế tiếp (801).
     * Mở lại camera và recorder cho đoạn mới. Khoảng ngắt được ghi vào cảnh báo.
     */
    private fun restartSegment() {
        if (!sessionActive) return
        nextSegment?.let { segments.finalize(it) }
        nextSegment = null
        recorder.stop()
        recorder.release()
        camera.close()
        currentSegment?.let { segments.finalize(it) }
        currentSegment = null
        gaps += "Có khoảng ngắt khi chuyển sang đoạn ${segmentIndex + 1}"
        segmentIndex += 1
        camHandler.postDelayed({ startSegment() }, RESTART_DELAY_MS)
    }

    private fun failWith(message: String) = finishSession(message, error = true)

    /**
     * Kết thúc phiên. Gọi được từ mọi thread. Việc dọn dẹp chạy trên nc-camera:
     * dừng recorder → đóng camera → hoàn tất file → nhả wake lock → dừng service.
     */
    private fun finishSession(reason: String, error: Boolean = false) {
        camHandler.post {
            var message = reason
            if (sessionActive) {
                sessionActive = false
                SegmentStore.writing = false
                _state.value = _state.value.copy(status = RecStatus.FINALIZING, message = reason)
                val stopped = recorder.stop()
                camera.close()
                recorder.release()
                currentSegment?.let { segments.finalize(it) }
                currentSegment = null
                nextSegment?.let { segments.finalize(it) }
                nextSegment = null
                plan = null
                releaseWakeLock()
                if (!stopped) message = "$reason (đoạn cuối có thể không đọc được)"
            }
            _state.value = RecState(
                status = if (error) RecStatus.ERROR else RecStatus.IDLE,
                message = message,
            )
            mainHandler.removeCallbacks(notifyTicker)
            mainHandler.post {
                ServiceCompat.stopForeground(this, STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun antibandingFor(caps: DeviceCaps): Int? = when {
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ in caps.antibandingModes ->
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
        CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO in caps.antibandingModes ->
            CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        else -> null
    }

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
        private const val STOP_MESSAGE = "Đã dừng"
    }
}
