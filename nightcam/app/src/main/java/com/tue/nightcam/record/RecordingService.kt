package com.tue.nightcam.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
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
 */
class RecordingService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val binder = LocalBinder()

    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()

    private lateinit var camThread: HandlerThread
    private lateinit var camHandler: Handler
    private lateinit var camera: CameraController
    private lateinit var recorder: RecorderController
    private lateinit var segments: SegmentStore
    private lateinit var mainHandler: Handler

    private var currentSegment: SegmentStore.Segment? = null
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

    override fun onBind(intent: Intent): IBinder? {
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

    /** Chạy trên thread nc-camera. */
    private fun beginRecording(requested: RecordingConfig, rotationDeg: Int) {
        try {
            val caps = CapabilityProbe.probe(this)
                ?: return failWith("Không tìm thấy camera sau.")
            val result = ConfigValidator.validate(
                if (audioEnabled) requested else requested.copy(audio = false),
                caps,
            )
            val cfg = result.config
            val fps = FpsRangePicker.pick(caps.fpsRanges, cfg.profileId, cfg.minFpsFloor)
                ?.let { Range(it.first, it.last) }
            val hint = Calc.orientationFor(cfg.orientationId, caps.sensorOrientation, rotationDeg)

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val segment = segments.create(stamp, 1)
            currentSegment = segment
            val surface = recorder.prepare(cfg, segment.pfd.fileDescriptor, hint)

            acquireWakeLock()
            _state.value = RecState(
                status = RecStatus.PREPARING,
                segmentIndex = 1,
                fileName = segment.name,
                message = "Đang mở camera…",
                warnings = result.warnings,
            )
            camera.open(caps.cameraId) {
                // Nếu người dùng đã dừng trong lúc camera đang mở thì đóng camera ngay.
                if (_state.value.status != RecStatus.PREPARING) {
                    camera.close()
                    return@open
                }
                try {
                    camera.startRecording(
                        surface = surface,
                        fpsRange = fps,
                        nrMode = if (cfg.nrId == RecordingConfig.NR_HQ) {
                            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                        } else {
                            CaptureRequest.NOISE_REDUCTION_MODE_FAST
                        },
                        ois = cfg.ois && caps.oisSupported,
                        antibandingMode = antibandingFor(caps),
                    ) {
                        onRecorderStarted(segment.name, result.warnings)
                    }
                } catch (e: Exception) {
                    failWith("Không bắt đầu được: ${e.message}")
                }
            }
        } catch (e: Exception) {
            failWith("Không bắt đầu được: ${e.message}")
        }
    }

    /** Chạy trên thread nc-camera, sau khi session đã có repeating request. */
    private fun onRecorderStarted(fileName: String, warnings: List<String>) {
        try {
            recorder.start()
        } catch (e: Exception) {
            failWith("Recorder không chạy: ${e.message}")
            return
        }
        _state.value = RecState(
            status = RecStatus.RECORDING,
            startedAtMs = System.currentTimeMillis(),
            segmentIndex = 1,
            fileName = fileName,
            message = "",
            warnings = warnings,
        )
        mainHandler.post(notifyTicker)
        notifyForeground()
    }

    private fun antibandingFor(caps: DeviceCaps): Int? = when {
        CAPS_ANTIBANDING_50HZ in caps.antibandingModes -> CAPS_ANTIBANDING_50HZ
        CAPS_ANTIBANDING_AUTO in caps.antibandingModes -> CAPS_ANTIBANDING_AUTO
        else -> null
    }

    private fun failWith(message: String) = finishSession(message, error = true)

    /**
     * Kết thúc phiên. Chạy trên thread nc-camera nên gọi được từ mọi nơi.
     * Thứ tự: dừng recorder → đóng session/camera → hoàn tất file → nhả wake lock → dừng service.
     */
    private fun finishSession(reason: String, error: Boolean = false) {
        camHandler.post {
            if (_state.value.status == RecStatus.IDLE) {
                mainHandler.post { stopSelf() }
                return@post
            }
            _state.value = _state.value.copy(status = RecStatus.FINALIZING, message = reason)
            val stopped = recorder.stop()
            camera.close()
            recorder.release()
            currentSegment?.let { segments.finalize(it) }
            currentSegment = null
            releaseWakeLock()

            val finalMessage = if (stopped) reason else "$reason (đoạn cuối có thể không đọc được)"
            _state.value = RecState(
                status = if (error) RecStatus.ERROR else RecStatus.IDLE,
                message = finalMessage,
            )
            mainHandler.removeCallbacks(notifyTicker)
            mainHandler.post {
                ServiceCompat.stopForeground(this, STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
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
        private const val STOP_MESSAGE = "Đã dừng"

        private val CAPS_ANTIBANDING_50HZ = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ
        private val CAPS_ANTIBANDING_AUTO = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
    }
}
