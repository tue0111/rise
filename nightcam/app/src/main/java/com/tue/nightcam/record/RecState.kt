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
)
