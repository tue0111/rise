package com.tue.nightcam.log

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log một phiên quay: cấu hình, khả năng máy, từng đoạn, số liệu, lỗi và lý do dừng.
 * Không ghi nội dung hình hay tiếng. Khi kết thúc, file được copy sang Download/NightCam
 * để người dùng tự gửi khi cần chẩn đoán.
 */
class SessionLog private constructor(private val file: File) {

    private val writer = FileWriter(file, true)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Ghi một dòng và flush ngay. Lượng ghi nhỏ nên không cần buffer. */
    fun line(text: String) {
        try {
            writer.append(clock.format(Date())).append("  ").append(text).append('\n')
            writer.flush()
        } catch (_: Exception) {
            // ổ đĩa đầy hoặc đã đóng: log không được làm hỏng phiên quay
        }
    }

    /** Đóng file rồi copy sang Download/NightCam. Trả về true nếu copy thành công. */
    fun close(resolver: ContentResolver): Boolean {
        try {
            writer.close()
        } catch (_: Exception) {
            // đã đóng
        }
        return exportToDownloads(resolver, file)
    }

    companion object {
        private const val DOWNLOAD_PATH = "Download/NightCam/"

        fun open(context: Context, stamp: String): SessionLog {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            return SessionLog(File(dir, "session_$stamp.txt"))
        }

        private fun exportToDownloads(resolver: ContentResolver, file: File): Boolean {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOAD_PATH)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            return try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                true
            } catch (_: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        }
    }
}
