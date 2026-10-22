package com.tue.nightcam.core

/**
 * Cấu hình một phiên quay. Mọi lựa chọn lưu bằng id chuỗi, không lưu chỉ số
 * (docs/PLAN.md mục 3.4). Id lạ hoặc giá trị ngoài khoảng thì rơi về mặc định.
 */
data class RecordingConfig(
    val resolutionId: String = RESOLUTION_1080P,
    val codecId: String = CODEC_HEVC,
    val videoMbps: Int = 14,
    val profileId: String = PROFILE_NIGHT,
    val minFpsFloor: Int = 15,
    val audio: Boolean = true,
    val segmentMinutes: Int = 10,
    val reserveGb: Int = 1,
    val nrId: String = NR_FAST,
    val eis: Boolean = false,
    val ois: Boolean = true,
    val orientationId: String = ORIENTATION_AUTO,
    val thermalDowngrade: Boolean = true,
) {
    val width: Int get() = Resolutions.sizeOf(resolutionId).first
    val height: Int get() = Resolutions.sizeOf(resolutionId).second
    val videoBps: Long get() = videoMbps * 1_000_000L
    val audioBps: Long get() = if (audio) AUDIO_BPS else 0L
    val totalBps: Long get() = videoBps + audioBps
    val isHevc: Boolean get() = codecId == CODEC_HEVC

    /** Số byte tối đa của một segment, đã kẹp dưới giới hạn 4 GB của MediaRecorder. */
    val segmentBytes: Long
        get() = Calc.segmentBytes(videoBps, audioBps, segmentMinutes * 60L)

    fun toMap(): Map<String, String> = mapOf(
        KEY_RESOLUTION to resolutionId,
        KEY_CODEC to codecId,
        KEY_VIDEO_MBPS to videoMbps.toString(),
        KEY_PROFILE to profileId,
        KEY_FPS_FLOOR to minFpsFloor.toString(),
        KEY_AUDIO to audio.toString(),
        KEY_SEGMENT_MIN to segmentMinutes.toString(),
        KEY_RESERVE_GB to reserveGb.toString(),
        KEY_NR to nrId,
        KEY_EIS to eis.toString(),
        KEY_OIS to ois.toString(),
        KEY_ORIENTATION to orientationId,
        KEY_THERMAL to thermalDowngrade.toString(),
    )

    companion object {
        const val RESOLUTION_2160P = "2160p"
        const val RESOLUTION_1080P = "1080p"
        const val RESOLUTION_720P = "720p"

        const val CODEC_HEVC = "hevc"
        const val CODEC_AVC = "avc"

        const val PROFILE_NIGHT = "night"
        const val PROFILE_STANDARD = "standard"

        const val NR_FAST = "fast"
        const val NR_HQ = "hq"

        const val ORIENTATION_AUTO = "auto"
        const val ORIENTATION_LANDSCAPE = "landscape"
        const val ORIENTATION_PORTRAIT = "portrait"

        const val AUDIO_BPS = 128_000L

        /** MediaRecorder giới hạn 4 GB; để dư 0,5 GB cho container. */
        const val MAX_SEGMENT_BYTES_CAP = 3_500_000_000L

        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_CODEC = "codec"
        private const val KEY_VIDEO_MBPS = "video_mbps"
        private const val KEY_PROFILE = "profile"
        private const val KEY_FPS_FLOOR = "fps_floor"
        private const val KEY_AUDIO = "audio"
        private const val KEY_SEGMENT_MIN = "segment_min"
        private const val KEY_RESERVE_GB = "reserve_gb"
        private const val KEY_NR = "nr"
        private const val KEY_EIS = "eis"
        private const val KEY_OIS = "ois"
        private const val KEY_ORIENTATION = "orientation"
        private const val KEY_THERMAL = "thermal_downgrade"

        /** Đọc lại từ map đã lưu. Thiếu key hoặc giá trị hỏng thì dùng mặc định. */
        fun fromMap(map: Map<String, String?>): RecordingConfig {
            val d = RecordingConfig()
            return RecordingConfig(
                resolutionId = map[KEY_RESOLUTION].takeIf { it in Resolutions.IDS } ?: d.resolutionId,
                codecId = map[KEY_CODEC].takeIf { it == CODEC_HEVC || it == CODEC_AVC } ?: d.codecId,
                videoMbps = map.intIn(KEY_VIDEO_MBPS, 1..60, d.videoMbps),
                profileId = map[KEY_PROFILE].takeIf { it == PROFILE_NIGHT || it == PROFILE_STANDARD } ?: d.profileId,
                minFpsFloor = map.intIn(KEY_FPS_FLOOR, 7..30, d.minFpsFloor),
                audio = map.boolOr(KEY_AUDIO, d.audio),
                segmentMinutes = map.intIn(KEY_SEGMENT_MIN, 1..60, d.segmentMinutes),
                reserveGb = map.intIn(KEY_RESERVE_GB, 0..64, d.reserveGb),
                nrId = map[KEY_NR].takeIf { it == NR_FAST || it == NR_HQ } ?: d.nrId,
                eis = map.boolOr(KEY_EIS, d.eis),
                ois = map.boolOr(KEY_OIS, d.ois),
                orientationId = map[KEY_ORIENTATION]
                    .takeIf { it == ORIENTATION_AUTO || it == ORIENTATION_LANDSCAPE || it == ORIENTATION_PORTRAIT }
                    ?: d.orientationId,
                thermalDowngrade = map.boolOr(KEY_THERMAL, d.thermalDowngrade),
            )
        }

        private fun Map<String, String?>.intIn(key: String, range: IntRange, default: Int): Int =
            this[key]?.toIntOrNull()?.takeIf { it in range } ?: default

        private fun Map<String, String?>.boolOr(key: String, default: Boolean): Boolean =
            this[key]?.toBooleanStrictOrNull() ?: default
    }
}

/** Kích thước khung hình theo id. Luôn ở hướng ngang; xoay do orientationHint. */
object Resolutions {
    val IDS = setOf(
        RecordingConfig.RESOLUTION_2160P,
        RecordingConfig.RESOLUTION_1080P,
        RecordingConfig.RESOLUTION_720P,
    )

    fun sizeOf(id: String): Pair<Int, Int> = when (id) {
        RecordingConfig.RESOLUTION_2160P -> 3840 to 2160
        RecordingConfig.RESOLUTION_720P -> 1280 to 720
        else -> 1920 to 1080
    }
}

data class Preset(val id: String, val label: String, val config: RecordingConfig)

object Presets {
    val NIGHT_FIXED = Preset(
        id = "night_fixed",
        label = "Đêm cố định",
        config = RecordingConfig(),
    )

    val NIGHT_MOVING = Preset(
        id = "night_moving",
        label = "Đêm có chuyển động",
        config = RecordingConfig(videoMbps = 16, minFpsFloor = 24),
    )

    val DAY_LONG = Preset(
        id = "day_long",
        label = "Ban ngày dài",
        config = RecordingConfig(videoMbps = 10, profileId = RecordingConfig.PROFILE_STANDARD),
    )

    val DETAIL_4K = Preset(
        id = "detail_4k",
        label = "Chi tiết cao (4K)",
        config = RecordingConfig(
            resolutionId = RecordingConfig.RESOLUTION_2160P,
            videoMbps = 40,
            profileId = RecordingConfig.PROFILE_STANDARD,
        ),
    )

    val ALL: List<Preset> = listOf(NIGHT_FIXED, NIGHT_MOVING, DAY_LONG, DETAIL_4K)

    fun byId(id: String?): Preset? = ALL.firstOrNull { it.id == id }
}
