package com.tue.nightcam.probe

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.os.Build
import com.tue.nightcam.core.DeviceCaps

/**
 * Đọc khả năng camera sau và encoder của máy. Trả về null nếu không có camera sau.
 * Chạy được trên thread nền. Không cần quyền runtime.
 */
object CapabilityProbe {

    fun probe(context: Context): DeviceCaps? {
        val cm = context.getSystemService(CameraManager::class.java) ?: return null
        val backId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: return null
        val ch = cm.getCameraCharacteristics(backId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null

        val sizes = (map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray())
            .map { it.width to it.height }
        val fps = (ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray())
            .map { it.lower..it.upper }
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val focusCal = ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
        val ev = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val active = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val hevc = encoder("video/hevc")
        val avc = encoder("video/avc")

        return DeviceCaps(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdk = Build.VERSION.SDK_INT,
            cameraId = backId,
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            hardwareLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
            recordSizes = sizes,
            fpsRanges = fps,
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            focusCalibrated = focusCal != null &&
                focusCal != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED,
            nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
                ?.toSet().orEmpty(),
            antibandingModes = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
                ?.toSet().orEmpty(),
            eisSupported = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true,
            oisSupported = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
            evRange = ev?.let { it.lower..it.upper } ?: 0..0,
            evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                ?.let { it.numerator.toFloat() / it.denominator } ?: 0f,
            hevcHardware = hevc.first,
            hevcMaxBps = hevc.second,
            avcHardware = avc.first,
            avcMaxBps = avc.second,
            exposureRangeNs = exp?.let { it.lower..it.upper },
            isoRange = iso?.let { it.lower..it.upper },
            activeWidth = active?.width() ?: 0,
            activeHeight = active?.height() ?: 0,
        )
    }

    /** (có encoder phần cứng không, bitrate tối đa bps). */
    private fun encoder(mime: String): Pair<Boolean, Int?> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        val hw: MediaCodecInfo? = infos.firstOrNull { it.isHardwareAccelerated }
        val max = hw?.getCapabilitiesForType(mime)?.videoCapabilities?.bitrateRange?.upper
        return (hw != null) to max
    }
}
