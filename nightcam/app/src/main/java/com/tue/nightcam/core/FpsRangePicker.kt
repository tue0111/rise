package com.tue.nightcam.core

/**
 * Chọn dải FPS cho AE (CAMERA2_NOTES mục 5).
 * Night: dải có trần 30 và sàn thấp nhất nhưng không dưới minFloor. Sàn thấp hơn cho phép
 * phơi sáng lâu hơn khi tối, đổi lại chuyển động nhòe và FPS thay đổi.
 * Standard: khóa cứng 30 fps.
 */
object FpsRangePicker {

    fun pick(ranges: List<IntRange>, profileId: String, minFloor: Int): IntRange? {
        if (ranges.isEmpty()) return null
        val capAt30 = ranges.filter { it.last == 30 }
        return if (profileId == RecordingConfig.PROFILE_NIGHT) {
            capAt30.filter { it.first >= minFloor }.minByOrNull { it.first }
                ?: capAt30.minByOrNull { it.first }
                ?: ranges.maxByOrNull { it.last }
        } else {
            ranges.firstOrNull { it.first == 30 && it.last == 30 }
                ?: capAt30.maxByOrNull { it.first }
                ?: ranges.maxByOrNull { it.last }
        }
    }
}
