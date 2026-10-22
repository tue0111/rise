package com.tue.nightcam.core

/** Quyết định sức khỏe phiên quay (docs/PLAN.md mục 3.5). Thuần Kotlin để test được. */
object HealthRules {

    /** Giá trị PowerManager.THERMAL_STATUS_*: SEVERE = 3, CRITICAL = 4. */
    const val THERMAL_SEVERE = 3
    const val THERMAL_CRITICAL = 4

    const val STALL_SECONDS = 60L
    const val MAX_RESTARTS_PER_HOUR = 3
    const val LOW_BATTERY_PERCENT = 5

    sealed class Decision {
        data object Continue : Decision()
        data class Downgrade(val reason: String) : Decision()
        data class Stop(val reason: String) : Decision()
    }

    fun storage(freeBytes: Long, reserveBytes: Long): Decision =
        if (freeBytes < reserveBytes) {
            Decision.Stop("Hết dung lượng (còn ${gb(freeBytes)} GB)")
        } else {
            Decision.Continue
        }

    /** Pin dưới ngưỡng và không cắm sạc thì dừng để file kịp đóng đúng. */
    fun battery(percent: Int, plugged: Boolean): Decision =
        if (!plugged && percent < LOW_BATTERY_PERCENT) {
            Decision.Stop("Pin yếu ($percent %)")
        } else {
            Decision.Continue
        }

    fun thermal(status: Int, downgradeEnabled: Boolean): Decision = when {
        status >= THERMAL_CRITICAL -> Decision.Stop("Máy quá nóng, dừng để bảo vệ file")
        status >= THERMAL_SEVERE && downgradeEnabled -> Decision.Downgrade("Máy nóng, hạ chất lượng")
        else -> Decision.Continue
    }

    /** File không lớn thêm trong [STALL_SECONDS] giây thì coi là đứng. */
    fun stalled(lastSize: Long, currentSize: Long, secondsSinceGrowth: Long): Boolean =
        currentSize <= lastSize && secondsSinceGrowth >= STALL_SECONDS

    fun restartAllowed(restartTimesMs: List<Long>, nowMs: Long): Boolean =
        restartTimesMs.count { nowMs - it < 3_600_000L } < MAX_RESTARTS_PER_HOUR

    /** Hạ cấu hình: 4K xuống 1080p, còn lại giảm bitrate 25 %, tối thiểu 6 Mbps. */
    fun downgrade(cfg: RecordingConfig): RecordingConfig =
        if (cfg.resolutionId == RecordingConfig.RESOLUTION_2160P) {
            cfg.copy(resolutionId = RecordingConfig.RESOLUTION_1080P)
        } else {
            cfg.copy(videoMbps = (cfg.videoMbps * 3 / 4).coerceAtLeast(6))
        }

    private fun gb(bytes: Long): String = "%.1f".format(bytes / 1e9)
}
