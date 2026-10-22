package com.tue.nightcam.core

/** Chế độ lấy nét. Đồng bộ với CONTROL_AF_MODE và LENS_FOCUS_DISTANCE khi dựng request. */
sealed class FocusMode {
    /** Bám nét liên tục theo chế độ video của camera. */
    data object Continuous : FocusMode()

    /** Khóa khoảng cách nét bằng diopter (1/mét). 0 là vô cực. */
    data class Locked(val diopters: Float) : FocusMode()

    /** Vừa chạm lấy nét, chờ AF khóa. Hết hạn [deadlineMs] thì chuyển sang khóa hoặc bám liên tục. */
    data class Tap(val box: Box, val deadlineMs: Long) : FocusMode()
}

/** Vùng đo theo hệ tọa độ active array của cảm biến. */
data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

object FocusRules {

    /** Quy tắc sau mỗi frame khi đang ở chế độ Tap. */
    fun afterFrame(current: FocusMode.Tap, afLocked: Boolean, distance: Float?, nowMs: Long): FocusMode {
        val done = afLocked || nowMs >= current.deadlineMs
        if (!done) return current
        return if (distance != null) FocusMode.Locked(distance) else FocusMode.Continuous
    }

    /** Hộp đo chạm: cạnh bằng 10% cạnh ngắn của active array, giữ trong khung. */
    fun tapBox(sensorX: Float, sensorY: Float, activeW: Int, activeH: Int): Box {
        val side = (minOf(activeW, activeH) * 0.1f).toInt().coerceAtLeast(2)
        val cx = (sensorX * activeW).toInt()
        val cy = (sensorY * activeH).toInt()
        val left = (cx - side / 2).coerceIn(0, activeW - side)
        val top = (cy - side / 2).coerceIn(0, activeH - side)
        return Box(left, top, side, side)
    }

    const val TAP_TIMEOUT_MS = 3_000L
}
