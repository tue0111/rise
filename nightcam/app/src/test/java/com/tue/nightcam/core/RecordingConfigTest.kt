package com.tue.nightcam.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingConfigTest {

    @Test
    fun roundTrip_keepsEveryField() {
        val cfg = RecordingConfig(
            resolutionId = RecordingConfig.RESOLUTION_2160P,
            codecId = RecordingConfig.CODEC_AVC,
            videoMbps = 33,
            profileId = RecordingConfig.PROFILE_STANDARD,
            minFpsFloor = 24,
            audio = false,
            segmentMinutes = 5,
            reserveGb = 3,
            nrId = RecordingConfig.NR_HQ,
            eis = true,
            ois = false,
            orientationId = RecordingConfig.ORIENTATION_LANDSCAPE,
            thermalDowngrade = false,
        )
        assertEquals(cfg, RecordingConfig.fromMap(cfg.toMap()))
    }

    @Test
    fun fromMap_missingKeysUseDefaults() {
        assertEquals(RecordingConfig(), RecordingConfig.fromMap(emptyMap()))
    }

    @Test
    fun fromMap_badValuesFallBackPerField() {
        val cfg = RecordingConfig.fromMap(
            mapOf(
                "resolution" to "8k",
                "video_mbps" to "9999",
                "fps_floor" to "abc",
                "audio" to "yes",
                "codec" to "hevc",
            ),
        )
        val d = RecordingConfig()
        assertEquals(d.resolutionId, cfg.resolutionId)
        assertEquals(d.videoMbps, cfg.videoMbps)
        assertEquals(d.minFpsFloor, cfg.minFpsFloor)
        assertEquals(d.audio, cfg.audio)
        assertEquals(RecordingConfig.CODEC_HEVC, cfg.codecId)
    }

    @Test
    fun dimensionsAndBitrates() {
        val cfg = RecordingConfig(resolutionId = RecordingConfig.RESOLUTION_2160P, videoMbps = 40)
        assertEquals(3840, cfg.width)
        assertEquals(2160, cfg.height)
        assertEquals(40_000_000L, cfg.videoBps)
        assertEquals(40_128_000L, cfg.totalBps)
        assertTrue(cfg.segmentBytes <= RecordingConfig.MAX_SEGMENT_BYTES_CAP)
    }

    @Test
    fun audioOff_addsNoAudioBitrate() {
        assertEquals(0L, RecordingConfig(audio = false).audioBps)
        assertFalse(RecordingConfig(audio = false).audioBps > 0)
    }

    @Test
    fun presetsHaveUniqueIds() {
        assertEquals(Presets.ALL.size, Presets.ALL.map { it.id }.toSet().size)
        assertEquals(Presets.NIGHT_FIXED, Presets.byId("night_fixed"))
        assertEquals(null, Presets.byId("nope"))
    }
}
