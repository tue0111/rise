package com.tue.nightcam.core

/** Quy tắc tên file và phục hồi, thuần Kotlin để test được. */
object Segments {

    fun fileName(stamp: String, index: Int): String = "NC_${stamp}_part${"%03d".format(index)}.mp4"

    /** Hành động với file đang ở trạng thái pending sau khi app chết. */
    enum class RecoveryAction { DELETE, PUBLISH, MARK_BROKEN }

    /**
     * size = 0 thì xóa. Có thời lượng đọc được thì công bố, vì phần lớn dữ liệu còn dùng được.
     * Không đọc được thời lượng thì đổi tên thành _BROKEN để người dùng tự quyết.
     */
    fun classifyPending(sizeBytes: Long, durationMs: Long?): RecoveryAction = when {
        sizeBytes <= 0L -> RecoveryAction.DELETE
        durationMs != null && durationMs > 0L -> RecoveryAction.PUBLISH
        else -> RecoveryAction.MARK_BROKEN
    }

    fun brokenName(name: String): String = "${name}_BROKEN"
}
