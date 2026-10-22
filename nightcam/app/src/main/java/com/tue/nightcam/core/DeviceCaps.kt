package com.tue.nightcam.core

/**
 * Khả năng của máy đo được lúc chạy (probe/CapabilityProbe). Lớp này thuần Kotlin
 * để ConfigValidator test được mà không cần thiết bị.
 */
data class DeviceCaps(
    val model: String,
    val sdk: Int,
    val cameraId: String,
    val sensorOrientation: Int,
    val hardwareLevel: Int,
    val recordSizes: List<Pair<Int, Int>>,
    val fpsRanges: List<IntRange>,
    val manualSensor: Boolean,
    val minFocusDiopters: Float,
    val focusCalibrated: Boolean,
    val nrModes: Set<Int>,
    val antibandingModes: Set<Int>,
    val eisSupported: Boolean,
    val oisSupported: Boolean,
    val evRange: IntRange,
    val evStep: Float,
    val hevcHardware: Boolean,
    val hevcMaxBps: Int?,
    val avcHardware: Boolean,
    val avcMaxBps: Int?,
    val exposureRangeNs: LongRange?,
    val isoRange: IntRange?,
    val activeWidth: Int,
    val activeHeight: Int,
) {
    fun supportsSize(width: Int, height: Int): Boolean = (width to height) in recordSizes

    companion object {
        /** Giá trị CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY. */
        const val NR_MODE_HIGH_QUALITY = 2
    }
}
