package com.tue.nightcam.core

/**
 * Đối chiếu cấu hình yêu cầu với khả năng máy. Không hỗ trợ thì sửa về thứ gần nhất
 * và ghi cảnh báo. Không bao giờ âm thầm ghi 720p dưới nhãn 1080p (PLAN mục 4).
 */
object ConfigValidator {

    data class Result(val config: RecordingConfig, val warnings: List<String>)

    fun validate(requested: RecordingConfig, caps: DeviceCaps): Result {
        val warnings = mutableListOf<String>()
        var c = requested

        // Độ phân giải: thử giảm dần 2160p → 1080p → 720p.
        if (!caps.supportsSize(c.width, c.height)) {
            val fallback = listOf(RecordingConfig.RESOLUTION_1080P, RecordingConfig.RESOLUTION_720P)
                .firstOrNull { Resolutions.sizeOf(it).let { (w, h) -> caps.supportsSize(w, h) } }
            if (fallback != null) {
                warnings += "Máy không quay được ${c.resolutionId}; chuyển sang $fallback."
                c = c.copy(resolutionId = fallback)
            } else {
                warnings += "Không tìm thấy kích thước quay phù hợp; vẫn thử ${c.resolutionId}."
            }
        }

        // Codec: không có HEVC phần cứng thì dùng H.264 với bitrate bù 40%.
        if (c.isHevc && !caps.hevcHardware) {
            if (caps.avcHardware) {
                val mbps = Calc.avcFallbackMbps(c.videoMbps)
                warnings += "Không có HEVC phần cứng; dùng H.264 ${mbps} Mbps."
                c = c.copy(codecId = RecordingConfig.CODEC_AVC, videoMbps = mbps)
            } else {
                warnings += "Không có encoder phần cứng cho HEVC; thử HEVC phần mềm."
            }
        }

        // Bitrate: kẹp theo tối đa của encoder đang dùng.
        val maxBps = if (c.isHevc) caps.hevcMaxBps else caps.avcMaxBps
        if (maxBps != null && c.videoBps > maxBps) {
            val mbps = (maxBps / 1_000_000).coerceAtLeast(1)
            warnings += "Bitrate ${c.videoMbps} Mbps vượt mức encoder; dùng $mbps Mbps."
            c = c.copy(videoMbps = mbps)
        }

        if (c.nrId == RecordingConfig.NR_HQ && DeviceCaps.NR_MODE_HIGH_QUALITY !in caps.nrModes) {
            warnings += "Máy không có chế độ khử nhiễu chất lượng cao; dùng nhanh."
            c = c.copy(nrId = RecordingConfig.NR_FAST)
        }
        if (c.ois && !caps.oisSupported) {
            warnings += "Máy không có OIS; tắt OIS."
            c = c.copy(ois = false)
        }
        if (c.eis && !caps.eisSupported) {
            warnings += "Máy không có EIS; tắt EIS."
            c = c.copy(eis = false)
        }
        if (FpsRangePicker.pick(caps.fpsRanges, c.profileId, c.minFpsFloor) == null) {
            warnings += "Máy không báo dải FPS nào; dùng mặc định của máy."
        }

        return Result(c, warnings)
    }
}
