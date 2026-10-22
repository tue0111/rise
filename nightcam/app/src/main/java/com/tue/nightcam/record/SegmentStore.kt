package com.tue.nightcam.record

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.tue.nightcam.core.Segments

/**
 * Phục hồi file video trong MediaStore. CameraX ghi file ở trạng thái IS_PENDING và chỉ công bố
 * khi hoàn tất; nếu app chết giữa chừng, file còn pending được xử lý ở lần mở sau.
 */
class SegmentStore(private val resolver: ContentResolver) {

    data class RecoveryResult(val published: Int, val removed: Int, val broken: Int) {
        val total: Int get() = published + removed + broken
    }

    /**
     * Quét các file còn pending của lần chạy trước (app bị chết giữa chừng).
     * Không chạy khi có phiên đang ghi, vì file của phiên đó cũng đang pending.
     */
    fun recoverPending(): RecoveryResult {
        if (writing) return RecoveryResult(0, 0, 0)
        var published = 0
        var removed = 0
        var broken = 0
        val query = MediaStore.setIncludePending(COLLECTION)
        resolver.query(
            query,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1",
            arrayOf(RELATIVE_PATH),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(COLLECTION, c.getLong(0))
                val name = c.getString(1) ?: continue
                val size = c.getLong(2)
                val duration = if (size > 0) readDurationMs(uri) else null
                when (Segments.classifyPending(size, duration)) {
                    Segments.RecoveryAction.DELETE -> {
                        resolver.delete(uri, null, null)
                        removed++
                    }
                    Segments.RecoveryAction.PUBLISH -> {
                        publish(uri)
                        published++
                    }
                    Segments.RecoveryAction.MARK_BROKEN -> {
                        resolver.update(
                            uri,
                            ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, Segments.brokenName(name))
                                put(MediaStore.Video.Media.IS_PENDING, 0)
                            },
                            null,
                            null,
                        )
                        broken++
                    }
                }
            }
        }
        return RecoveryResult(published, removed, broken)
    }

    private fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    }

    private fun readDurationMs(uri: Uri): Long? {
        val pfd = try {
            resolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            return null
        } ?: return null
        return pfd.use { p ->
            MediaMetadataRetriever().use { r ->
                try {
                    r.setDataSource(p.fileDescriptor)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    companion object {
        const val RELATIVE_PATH = "Movies/NightCam/"
        val COLLECTION: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        /**
         * True khi có phiên đang ghi trong process. Đặt bởi RecordingService.
         * Khi đó recoverPending không chạy.
         */
        @Volatile
        var writing: Boolean = false
    }
}
