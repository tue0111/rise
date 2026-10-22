package com.tue.nightcam.record

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/**
 * Tạo và hoàn tất file video trong MediaStore. Trong lúc ghi file ở trạng thái IS_PENDING,
 * nên thư viện không hiện file dở dang. Khi hoàn tất file mới được công bố.
 */
class SegmentStore(private val resolver: ContentResolver) {

    data class Segment(val uri: Uri, val pfd: ParcelFileDescriptor, val name: String)

    fun create(stamp: String, index: Int): Segment {
        val name = "NC_${stamp}_part${"%03d".format(index)}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(COLLECTION, values) ?: error("MediaStore không tạo được $name")
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        if (pfd == null) {
            resolver.delete(uri, null, null)
            error("Không mở được $name để ghi")
        }
        return Segment(uri, pfd, name)
    }

    /**
     * Đóng segment. File rỗng bị xóa. File có dữ liệu được công bố (IS_PENDING = 0).
     * Kích thước được đọc lại từ MediaStore sau khi đóng, vì statSize có thể là -1.
     */
    fun finalize(segment: Segment) {
        try {
            segment.pfd.close()
        } catch (_: Exception) {
            // đã đóng hoặc lỗi I/O: vẫn kiểm tra kích thước bên dưới
        }
        val size = resolver.query(segment.uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        if (size == 0L) {
            resolver.delete(segment.uri, null, null)
            return
        }
        resolver.update(
            segment.uri,
            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }

    companion object {
        const val RELATIVE_PATH = "Movies/NightCam/"
        val COLLECTION: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }
}
