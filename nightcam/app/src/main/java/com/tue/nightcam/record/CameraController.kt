package com.tue.nightcam.record

import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Sở hữu camera và session. Camera có thể giữ mở cho preview rồi đổi session sang ghi hình
 * mà không mở lại thiết bị. Mọi callback chạy trên [handler] (thread nc-camera).
 * Chỉ RecordingService được gọi class này.
 */
class CameraController(
    private val cameraManager: CameraManager,
    private val handler: Handler,
) {

    private val exec = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var opening = false
    private val openWaiters = mutableListOf<() -> Unit>()

    private var template = CameraDevice.TEMPLATE_PREVIEW
    private var targets: List<Surface> = emptyList()
    private var configure: (CaptureRequest.Builder) -> Unit = {}

    /** Gọi khi camera lỗi hoặc bị ngắt giữa chừng. */
    var onFailure: ((String) -> Unit)? = null

    /** Gọi cho mỗi frame đã hoàn tất (dùng cho lấy nét). */
    var onFrame: ((CaptureResult) -> Unit)? = null

    val isOpen: Boolean get() = device != null

    /**
     * Mở camera. Nếu đang mở rồi thì gọi [onOpened] ngay. Nếu đang mở dở thì xếp hàng
     * để không mở camera hai lần song song.
     */
    fun open(cameraId: String, onOpened: () -> Unit) {
        if (device != null) {
            onOpened()
            return
        }
        openWaiters += onOpened
        if (opening) return
        opening = true
        cameraManager.openCamera(cameraId, exec, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                opening = false
                val waiters = openWaiters.toList()
                openWaiters.clear()
                waiters.forEach { it() }
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                failOpen("Mất kết nối camera")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                failOpen("Lỗi camera (mã $error)")
            }
        })
    }

    private fun failOpen(message: String) {
        device = null
        opening = false
        openWaiters.clear()
        onFailure?.invoke(message)
    }

    /**
     * Tạo session mới với các surface đích, đóng session cũ trước. Khi session sẵn sàng
     * và đã có repeating request thì gọi [onReady].
     */
    fun startSession(
        targets: List<Surface>,
        template: Int,
        configure: (CaptureRequest.Builder) -> Unit,
        onReady: () -> Unit,
    ) {
        val dev = device ?: error("Camera chưa mở")
        closeSession()
        this.targets = targets
        this.template = template
        this.configure = configure
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            targets.map { OutputConfiguration(it) },
            exec,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(buildRequest(dev, configure, triggerAf = false), frameCallback, handler)
                    } catch (e: CameraAccessException) {
                        onFailure?.invoke("Không gửi được yêu cầu quay (${e.reason})")
                        return
                    }
                    onReady()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onFailure?.invoke("Không tạo được phiên quay")
                }
            },
        )
        dev.createCaptureSession(sessionConfig)
    }

    /** Cập nhật repeating request của session hiện tại (đổi EV, chế độ lấy nét…). */
    fun updateRequest(configure: (CaptureRequest.Builder) -> Unit) {
        this.configure = configure
        val dev = device ?: return
        val s = session ?: return
        try {
            s.setRepeatingRequest(buildRequest(dev, configure, triggerAf = false), frameCallback, handler)
        } catch (e: CameraAccessException) {
            onFailure?.invoke("Không cập nhật được camera (${e.reason})")
        }
    }

    /** Gửi một lần kích hoạt AF. Các thiết lập lấy nét đọc từ [configure]. */
    fun triggerAf(configure: (CaptureRequest.Builder) -> Unit) {
        val dev = device ?: return
        val s = session ?: return
        try {
            s.capture(buildRequest(dev, configure, triggerAf = true), null, handler)
        } catch (e: CameraAccessException) {
            onFailure?.invoke("Không kích hoạt được lấy nét (${e.reason})")
        }
    }

    private fun buildRequest(
        dev: CameraDevice,
        configure: (CaptureRequest.Builder) -> Unit,
        triggerAf: Boolean,
    ): CaptureRequest = dev.createCaptureRequest(template).run {
        targets.forEach { addTarget(it) }
        configure(this)
        set(
            CaptureRequest.CONTROL_AF_TRIGGER,
            if (triggerAf) CameraMetadata.CONTROL_AF_TRIGGER_START else CameraMetadata.CONTROL_AF_TRIGGER_IDLE,
        )
        build()
    }

    private val frameCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            onFrame?.invoke(result)
        }
    }

    private fun closeSession() {
        try {
            session?.close()
        } catch (_: IllegalStateException) {
            // đã đóng
        }
        session = null
    }

    /** Đóng session rồi camera. Không ném lỗi, vì luôn gọi trong đường dọn dẹp. */
    fun close() {
        closeSession()
        device?.close()
        device = null
    }
}
