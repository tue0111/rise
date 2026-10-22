package com.tue.nightcam.record

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.view.Surface
import com.tue.nightcam.core.RecordingConfig
import java.io.FileDescriptor

/**
 * Bọc MediaRecorder. Thứ tự gọi bắt buộc theo CAMERA2_NOTES mục 2:
 * audio source → video source → output format → output file → encoder → bitrate → size → prepare → lấy surface.
 * Sự kiện (802, 803, 801) được chuyển cho [onInfo]. Các callback này chạy trên thread đã tạo recorder.
 */
class RecorderController(private val appContext: Context) {

    private var recorder: MediaRecorder? = null

    /**
     * Chuẩn bị recorder và trả về surface để gắn vào camera session.
     * [maxBytes] là giới hạn đoạn để MediaRecorder báo khi sắp đầy.
     */
    fun prepare(
        config: RecordingConfig,
        output: FileDescriptor,
        orientationHint: Int,
        maxBytes: Long,
        onInfo: (what: Int) -> Unit,
        onError: (what: Int) -> Unit,
    ): Surface {
        val r = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(appContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            if (config.audio) {
                r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            }
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(output)
            r.setMaxFileSize(maxBytes)
            r.setVideoEncoder(
                if (config.isHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264,
            )
            r.setVideoEncodingBitRate(config.videoBps.toInt())
            r.setVideoFrameRate(TARGET_FPS_HINT)
            r.setVideoSize(config.width, config.height)
            r.setOrientationHint(orientationHint)
            if (config.audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(RecordingConfig.AUDIO_BPS.toInt())
                r.setAudioSamplingRate(48_000)
                r.setAudioChannels(1)
            }
            r.setOnInfoListener { _, what, _ -> onInfo(what) }
            r.setOnErrorListener { _, what, _ -> onError(what) }
            r.prepare()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
        return r.surface
    }

    /** Đặt file kế tiếp, dùng khi MediaRecorder báo sắp đầy đoạn (API 26+). */
    fun setNextOutput(output: FileDescriptor) {
        recorder?.setNextOutputFile(output) ?: error("Recorder chưa được chuẩn bị")
    }

    fun start() {
        recorder?.start() ?: error("Recorder chưa được chuẩn bị")
    }

    /** Dừng recorder. Trả về false nếu dừng lỗi (thường là chưa có frame nào). */
    fun stop(): Boolean {
        val r = recorder ?: return false
        return try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun release() {
        recorder?.let {
            try {
                it.reset()
            } catch (_: RuntimeException) {
                // reset lỗi thì release vẫn phải chạy
            }
            it.release()
        }
        recorder = null
    }

    companion object {
        /** Gợi ý khung hình cho encoder. AE có thể đổi dải FPS (chế độ đêm 15–30). */
        const val TARGET_FPS_HINT = 30
    }
}
