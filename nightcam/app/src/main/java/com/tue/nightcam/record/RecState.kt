package com.tue.nightcam.record

enum class RecStatus { IDLE, PREPARING, RECORDING, FINALIZING, ERROR }

/** Trạng thái phiên quay, UI quan sát qua StateFlow. */
data class RecState(
    val status: RecStatus = RecStatus.IDLE,
    val startedAtMs: Long = 0L,
    val segmentIndex: Int = 0,
    val fileName: String = "",
    val message: String = "",
    val warnings: List<String> = emptyList(),
    /** Nhãn lấy nét hiển thị trên UI, ví dụ "Khóa nét ≈ 0,50 m". */
    val focusText: String = "",
    /** Nhãn bù sáng, ví dụ "EV −0,7". */
    val evText: String = "EV 0",
    /** Máy có lấy nét tay (có minFocusDiopters > 0). */
    val manualFocus: Boolean = false,
    /** Đang khóa AE và AWB. */
    val locked: Boolean = false,
    /** Số liệu đo được: FPS, màn trập, ISO. */
    val measureText: String = "",
    /** Pin, nhiệt và dung lượng còn lại, cập nhật mỗi 15 giây. */
    val healthText: String = "",
)
