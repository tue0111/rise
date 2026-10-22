package com.tue.nightcam.preview

import android.graphics.Matrix
import android.graphics.RectF
import android.view.TextureView
import kotlin.math.max

/**
 * Xoay và cắt đầy khung preview. Buffer của camera là ngang (1920×1080), còn màn hình là dọc.
 * Công thức theo mẫu Camera2Basic của Android, rồi đổi góc theo sensorOrientation.
 *
 * CHƯA KIỂM CHỨNG trên Pixel 3 XL. Nếu ảnh bị nằm ngang hoặc lộn ngược, đổi dấu [rotationFor].
 */
object PreviewTransform {

    const val BUFFER_WIDTH = 1920
    const val BUFFER_HEIGHT = 1080

    fun apply(view: TextureView, sensorOrientation: Int) {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw == 0f || vh == 0f) return

        val viewRect = RectF(0f, 0f, vw, vh)
        val bufferRect = RectF(0f, 0f, BUFFER_HEIGHT.toFloat(), BUFFER_WIDTH.toFloat())
        val cx = viewRect.centerX()
        val cy = viewRect.centerY()
        bufferRect.offset(cx - bufferRect.centerX(), cy - bufferRect.centerY())

        val matrix = Matrix()
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = max(vh / BUFFER_HEIGHT, vw / BUFFER_WIDTH)
        matrix.postScale(scale, scale, cx, cy)
        matrix.postRotate(rotationFor(sensorOrientation), cx, cy)
        view.setTransform(matrix)
    }

    /** Góc xoay của buffer để thẳng khi cầm dọc. Xem ghi chú ở đầu file. */
    fun rotationFor(sensorOrientation: Int): Float = -sensorOrientation.toFloat()
}
