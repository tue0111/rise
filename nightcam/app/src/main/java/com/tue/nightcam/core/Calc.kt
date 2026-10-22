package com.tue.nightcam.core

import kotlin.math.roundToInt

/** Các phép tính thuần, không phụ thuộc Android. Xem docs/PLAN.md mục 5 và CAMERA2_NOTES mục 8. */
object Calc {

    /** Tổng bitrate Mbps → GB mỗi giờ (1 Mbps × 3600 s / 8 bit / 1000 = 0,45 GB). */
    fun gbPerHour(totalMbps: Double): Double = totalMbps * 0.45

    /** Số byte cho một segment, kẹp dưới giới hạn 3,5 GB của MediaRecorder. */
    fun segmentBytes(videoBps: Long, audioBps: Long, seconds: Long): Long =
        minOf((videoBps + audioBps) / 8 * seconds, RecordingConfig.MAX_SEGMENT_BYTES_CAP)

    /**
     * Số giờ còn quay được. Null khi không biết bitrate (≤ 0).
     * Trừ phần dự phòng trước khi chia.
     */
    fun hoursRemaining(freeBytes: Long, reserveBytes: Long, totalBps: Long): Double? {
        if (totalBps <= 0) return null
        val usable = (freeBytes - reserveBytes).coerceAtLeast(0)
        return usable.toDouble() / (totalBps / 8.0) / 3600.0
    }

    /**
     * Góc xoay cho MediaRecorder.setOrientationHint với camera sau.
     * Công thức chuẩn của Camera2: (sensorOrientation − góc xoay màn hình) mod 360.
     */
    fun orientationHint(sensorOrientation: Int, displayRotationDeg: Int): Int =
        mod360(sensorOrientation - displayRotationDeg)

    /**
     * Hướng xoay theo chế độ người dùng chọn (RecordingConfig.orientationId).
     * Auto theo màn hình; Ngang/Dọc ép về hướng gần nhất với màn hình hiện tại.
     */
    fun orientationFor(mode: String, sensorOrientation: Int, displayRotationDeg: Int): Int {
        val rot = roundToRightAngle(displayRotationDeg)
        return when (mode) {
            RecordingConfig.ORIENTATION_LANDSCAPE ->
                orientationHint(sensorOrientation, if (rot == 90 || rot == 270) rot else 90)
            RecordingConfig.ORIENTATION_PORTRAIT ->
                orientationHint(sensorOrientation, if (rot == 0 || rot == 180) rot else 0)
            else -> orientationHint(sensorOrientation, rot)
        }
    }

    /** Thời lượng dạng HH:MM:SS. */
    fun formatDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }

    /** Làm tròn góc bất kỳ về 0 / 90 / 180 / 270. */
    fun roundToRightAngle(deg: Int): Int = (mod360(deg) + 45) / 90 % 4 * 90

    /**
     * Đổi điểm chạm (nx, ny) trong khung preview dọc, giá trị 0..1, sang hệ tọa độ cảm biến.
     * Ánh xạ theo sensorOrientation. Cần kiểm tra lại trên máy thật.
     */
    fun touchToSensor(nx: Float, ny: Float, sensorOrientation: Int): Pair<Float, Float> =
        when (roundToRightAngle(sensorOrientation)) {
            90 -> ny to 1f - nx
            180 -> 1f - nx to 1f - ny
            270 -> 1f - ny to nx
            else -> nx to ny
        }

    /** Bitrate AVC bù cho HEVC khi không có encoder HEVC phần cứng. */
    fun avcFallbackMbps(hevcMbps: Int): Int = (hevcMbps * 1.4f).roundToInt()

    private fun mod360(deg: Int): Int = ((deg % 360) + 360) % 360
}
