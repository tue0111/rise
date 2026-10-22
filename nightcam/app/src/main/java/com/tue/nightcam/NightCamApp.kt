package com.tue.nightcam

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ghi lỗi crash ra Download/NightCam/crash_<thời điểm>.txt để người dùng gửi lại,
 * vì trên máy không có adb để xem logcat.
 */
class NightCamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrash(thread, error)
            } catch (_: Throwable) {
                // không được để bộ ghi crash gây thêm lỗi
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrash(thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val text = buildString {
            appendLine("NightCam ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(error.stackTraceToString())
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "crash_$stamp.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/NightCam/")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }
}
