package com.tue.nightcam.probe

import com.tue.nightcam.core.DeviceCaps
import org.json.JSONArray
import org.json.JSONObject

/** Xuất DeviceCaps thành JSON để hiển thị và đưa vào log phiên (T9). */
fun DeviceCaps.toJson(): JSONObject = JSONObject().apply {
    put("model", model)
    put("sdk", sdk)
    put("camera_id", cameraId)
    put("sensor_orientation", sensorOrientation)
    put("hardware_level", hardwareLevel)
    put("record_sizes", JSONArray(recordSizes.map { "${it.first}x${it.second}" }))
    put("fps_ranges", JSONArray(fpsRanges.map { "${it.first}-${it.last}" }))
    put("manual_sensor", manualSensor)
    put("min_focus_diopters", minFocusDiopters.toDouble())
    put("focus_calibrated", focusCalibrated)
    put("nr_modes", JSONArray(nrModes.sorted()))
    put("antibanding_modes", JSONArray(antibandingModes.sorted()))
    put("eis", eisSupported)
    put("ois", oisSupported)
    put("ev_range", "${evRange.first}..${evRange.last}")
    put("ev_step", evStep.toDouble())
    put("hevc_hw", hevcHardware)
    put("hevc_max_bps", hevcMaxBps ?: JSONObject.NULL)
    put("avc_hw", avcHardware)
    put("avc_max_bps", avcMaxBps ?: JSONObject.NULL)
    put("exposure_ns", exposureRangeNs?.let { "${it.first}..${it.last}" } ?: JSONObject.NULL)
    put("iso", isoRange?.let { "${it.first}..${it.last}" } ?: JSONObject.NULL)
}
