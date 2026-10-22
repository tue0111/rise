package com.tue.nightcam.record

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.util.Range
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Sở hữu camera và session. Mọi callback chạy trên [handler] (thread nc-camera).
 * Chỉ RecordingService được gọi class này.
 */
class CameraController(
    private val cameraManager: CameraManager,
    private val handler: Handler,
) {

    private val exec = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    /** Gọi khi camera lỗi hoặc bị ngắt giữa chừng. */
    var onFailure: ((String) -> Unit)? = null

    fun open(cameraId: String, onOpened: () -> Unit) {
        cameraManager.openCamera(cameraId, exec, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                onOpened()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                device = null
                onFailure?.invoke("Mất kết nối camera")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                device = null
                onFailure?.invoke("Lỗi camera (mã $error)")
            }
        })
    }

    /**
     * Tạo session chỉ có một surface (surface của MediaRecorder). Khi session sẵn sàng,
     * gửi repeating request rồi mới gọi [onReady] để bắt đầu recorder.
     */
    fun startRecording(
        surface: Surface,
        fpsRange: Range<Int>?,
        nrMode: Int,
        ois: Boolean,
        antibandingMode: Int?,
        onReady: () -> Unit,
    ) {
        val dev = device ?: error("Camera chưa mở")
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            exec,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(
                            buildRequest(dev, surface, fpsRange, nrMode, ois, antibandingMode),
                            null,
                            handler,
                        )
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

    private fun buildRequest(
        dev: CameraDevice,
        surface: Surface,
        fpsRange: Range<Int>?,
        nrMode: Int,
        ois: Boolean,
        antibandingMode: Int?,
    ): CaptureRequest = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).run {
        addTarget(surface)
        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        set(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)
        set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        if (fpsRange != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        if (antibandingMode != null) set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antibandingMode)
        if (ois) {
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        build()
    }

    /** Đóng session rồi camera. Không ném lỗi, vì luôn gọi trong đường dọn dẹp. */
    fun close() {
        try {
            session?.close()
        } catch (_: IllegalStateException) {
            // đã đóng
        }
        session = null
        device?.close()
        device = null
    }
}
