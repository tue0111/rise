package com.tue.nightcam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidatorTest {

    private fun caps(
        sizes: List<Pair<Int, Int>> = listOf(1920 to 1080, 1280 to 720, 3840 to 2160),
        hevcHw: Boolean = true,
        avcHw: Boolean = true,
        hevcMax: Int? = 50_000_000,
        avcMax: Int? = 40_000_000,
        nr: Set<Int> = setOf(1, 2),
        ois: Boolean = true,
        eis: Boolean = true,
        fps: List<IntRange> = listOf(15..30, 30..30),
    ) = DeviceCaps(
        model = "test", sdk = 31, cameraId = "0", sensorOrientation = 90, hardwareLevel = 3,
        recordSizes = sizes, fpsRanges = fps, manualSensor = true,
        minFocusDiopters = 10f, focusCalibrated = true, nrModes = nr, antibandingModes = setOf(2, 3),
        eisSupported = eis, oisSupported = ois, evRange = -4..4, evStep = 0.33f,
        hevcHardware = hevcHw, hevcMaxBps = hevcMax, avcHardware = avcHw, avcMaxBps = avcMax,
        exposureRangeNs = null, isoRange = null,
        activeWidth = 4000, activeHeight = 3000,
    )

    @Test
    fun supportedConfig_isUnchanged() {
        val cfg = RecordingConfig()
        val r = ConfigValidator.validate(cfg, caps())
        assertEquals(cfg, r.config)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun unsupported4k_fallsBackTo1080pWithWarning() {
        val r = ConfigValidator.validate(
            RecordingConfig(resolutionId = RecordingConfig.RESOLUTION_2160P),
            caps(sizes = listOf(1920 to 1080)),
        )
        assertEquals(RecordingConfig.RESOLUTION_1080P, r.config.resolutionId)
        assertTrue(r.warnings.single().contains("chuyển sang 1080p"))
    }

    @Test
    fun noHevcHardware_switchesToAvcWithBoostedBitrate() {
        val r = ConfigValidator.validate(RecordingConfig(videoMbps = 14), caps(hevcHw = false))
        assertEquals(RecordingConfig.CODEC_AVC, r.config.codecId)
        assertEquals(20, r.config.videoMbps)
        assertTrue(r.warnings.any { it.contains("H.264") })
    }

    @Test
    fun noHardwareAtAll_keepsHevcWithWarning() {
        val r = ConfigValidator.validate(RecordingConfig(), caps(hevcHw = false, avcHw = false))
        assertEquals(RecordingConfig.CODEC_HEVC, r.config.codecId)
        assertTrue(r.warnings.single().contains("phần mềm"))
    }

    @Test
    fun bitrateAboveEncoderMax_isClamped() {
        val r = ConfigValidator.validate(RecordingConfig(videoMbps = 40), caps(hevcMax = 16_000_000))
        assertEquals(16, r.config.videoMbps)
    }

    @Test
    fun unsupportedFeatures_areTurnedOff() {
        val r = ConfigValidator.validate(
            RecordingConfig(nrId = RecordingConfig.NR_HQ, ois = true, eis = true),
            caps(nr = setOf(1), ois = false, eis = false),
        )
        assertEquals(RecordingConfig.NR_FAST, r.config.nrId)
        assertFalse(r.config.ois)
        assertFalse(r.config.eis)
        assertEquals(3, r.warnings.size)
    }

    @Test
    fun noFpsRanges_warns() {
        val r = ConfigValidator.validate(RecordingConfig(), caps(fps = emptyList()))
        assertTrue(r.warnings.single().contains("dải FPS"))
    }
}
