# Ghi chú kỹ thuật Camera2 + MediaRecorder

Đây là các công thức đã chọn sẵn. **Làm đúng thứ tự gọi hàm như dưới đây**: Camera2 và MediaRecorder
không báo lỗi rõ ràng khi gọi sai thứ tự, chỉ ra khung đen, file 0 byte hoặc crash native.
Code mẫu viết bằng Kotlin, chỉ cần điều chỉnh cho khớp với class trong project.

---

## 0. Luồng (thread) và quyền sở hữu

- **`RecordingService` sở hữu `CameraController` mọi lúc**, kể cả lúc chỉ preview.
  Activity bind vào service và gọi `attachPreview(surface)` / `detachPreview()`.
  Không bao giờ có hai nơi cùng mở camera.
- Mọi lời gọi camera và MediaRecorder chạy trên **một `HandlerThread("nc-camera")`**.
  Executor cho API Camera2: `val exec = Executor { r -> handler.post(r) }`.
- **Tạo MediaRecorder trên thread đó**: callback `OnInfo`/`OnError` sẽ chạy trên Looper của thread tạo ra nó.
- Đẩy state ra UI bằng `MutableStateFlow`. Không bao giờ chặn main thread.

## 1. Thăm dò khả năng máy (CapabilityProbe)

```kotlin
val cm = ctx.getSystemService(CameraManager::class.java)
val backId = cm.cameraIdList.first {
    cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
        CameraCharacteristics.LENS_FACING_BACK
}
val ch = cm.getCameraCharacteristics(backId)
val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
val recSizes = map.getOutputSizes(MediaRecorder::class.java)          // 3840x2160, 1920x1080...
val minFrameNs = map.getOutputMinFrameDuration(MediaRecorder::class.java, size) // phải <= 33_333_333 cho 30fps
val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)!!
val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!.toSet()
val manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
val minFocusDiopters = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f // 0 = fixed focus
val focusCalib = ch.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
val nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
val antibanding = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
val eisModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
val oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
val evRange = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
val evStep = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
val activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
val maxAfRegions = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
val maxAeRegions = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
```

Encoder:

```kotlin
fun hwEncoder(mime: String, w: Int, h: Int, fps: Double): MediaCodecInfo? =
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
        info.isEncoder && info.isHardwareAccelerated &&            // API 29
            info.supportedTypes.any { it.equals(mime, true) } &&
            info.getCapabilitiesForType(mime).videoCapabilities
                ?.areSizeAndRateSupported(w, h, fps) == true
    }
// mime: MediaFormat.MIMETYPE_VIDEO_HEVC hoặc MIMETYPE_VIDEO_AVC
// bitrateRange = info.getCapabilitiesForType(mime).videoCapabilities.bitrateRange → clamp bitrate
```

Ghi toàn bộ kết quả thành JSON vào SessionLog, hiển thị ở màn "Thông tin máy".

## 2. Thiết lập MediaRecorder (THỨ TỰ BẮT BUỘC)

```kotlin
val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
if (cfg.audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
r.setOutputFile(firstSegment.pfd.fileDescriptor)
r.setMaxFileSize(cfg.segmentBytes)                      // bật cơ chế xoay segment
r.setVideoEncoder(if (cfg.hevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
r.setVideoEncodingBitRate(cfg.videoBps)
r.setVideoFrameRate(30)
r.setVideoSize(cfg.width, cfg.height)
r.setOrientationHint(orientationHint)                   // xem mục 8
if (cfg.audio) {
    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    r.setAudioEncodingBitRate(128_000)
    r.setAudioSamplingRate(48_000)
    r.setAudioChannels(1)
}
r.setOnInfoListener(infoListener)
r.setOnErrorListener { _, what, extra -> onRecorderError(what, extra) }
r.prepare()
val recSurface = r.surface        // CHỈ lấy sau prepare()
```

Sau đó tạo session camera với `recSurface` (mục 4). **`r.start()` chỉ gọi sau khi session đã
`onConfigured` VÀ đã `setRepeatingRequest`.**

Dừng:

```kotlin
try { r.stop() } catch (e: RuntimeException) { /* chưa nhận frame nào → segment hỏng, đánh dấu BROKEN */ }
session.close(); camera.close(); r.reset(); r.release()
```

Không gọi `stop()` ngay sau `start()` (dưới 1 s là throw). Muốn hủy sớm thì chờ đủ 1 s.

## 3. Xoay segment gần liền mạch (API 26+)

```kotlin
val infoListener = MediaRecorder.OnInfoListener { rec, what, _ ->
    when (what) {
        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_APPROACHING -> {   // 802, khoảng 90% dung lượng
            next = segmentStore.create(sessionId, index + 1)               // insert MediaStore pending + mở pfd "rw"
            rec.setNextOutputFile(next.pfd.fileDescriptor)
        }
        MediaRecorder.MEDIA_RECORDER_INFO_NEXT_OUTPUT_FILE_STARTED -> {  // 803
            segmentStore.finalize(current)                                 // đóng pfd cũ, IS_PENDING=0
            current = next; index++
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS)                          // gia hạn
        }
        MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED -> {        // 801, lỡ nhịp → recorder đã dừng
            // khởi động lại cả recorder + session cho segment mới, ghi log GAP
        }
    }
}
```

- `segmentBytes = videoBps/8 * segmentSeconds + audioBps/8 * segmentSeconds`, clamp ≤ 3_500_000_000.
- Giữ `pfd` của segment cũ mở tới khi nhận 803 rồi mới đóng.

## 4. Mở camera và tạo session

```kotlin
cm.openCamera(backId, exec, object : CameraDevice.StateCallback() {
    override fun onOpened(d: CameraDevice) { device = d; createSession(outputs) }
    override fun onDisconnected(d: CameraDevice) { d.close(); onCameraLost("disconnected") }
    override fun onError(d: CameraDevice, error: Int) { d.close(); onCameraLost("error $error") }
})

fun createSession(surfaces: List<Surface>) {
    val cfg = SessionConfiguration(
        SessionConfiguration.SESSION_REGULAR,
        surfaces.map { OutputConfiguration(it) },
        exec,
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                s.setRepeatingRequest(buildRequest(surfaces), captureCallback, handler)
                if (mode == RECORD) recorder.start()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { fail("configure failed") }
        })
    device.createCaptureSession(cfg)
}
```

- Session preview: `[previewSurface]`, buffer 1920×1080. Gọi `SurfaceTexture.setDefaultBufferSize(1920,1080)` **trước** khi tạo `Surface`.
- Session quay: `[recSurface]` **duy nhất** (PLAN 3.2).
- Chuyển từ preview sang quay: `session.close()` rồi `createSession([recSurface])`, giữ nguyên `device`.
- Activity khóa dọc. TextureView đặt `app:layout_constraintDimensionRatio="9:16"`:
  buffer 1920×1080 của camera sau (sensorOrientation 90) hiện đúng chiều, không cần `setTransform`.

## 5. Dựng CaptureRequest (một hàm duy nhất, mọi thay đổi đều đi qua đây)

```kotlin
fun buildRequest(targets: List<Surface>): CaptureRequest {
    val b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
    targets.forEach(b::addTarget)
    b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, ctl.fpsRange)        // FpsRangePicker
    b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, ctl.antibanding)     // 50HZ nếu có, không thì AUTO
    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ctl.evIndex)
    b.set(CaptureRequest.CONTROL_AE_LOCK, ctl.aeLock)
    b.set(CaptureRequest.CONTROL_AWB_LOCK, ctl.awbLock)
    b.set(CaptureRequest.NOISE_REDUCTION_MODE, ctl.nrMode)                  // FAST mặc định
    b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
    b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, ctl.eis)          // OFF mặc định
    if (ctl.oisSupported) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, ctl.ois)
    when (val f = ctl.focus) {
        is Focus.Continuous -> b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        is Focus.Locked -> {                          // khoảng cách đã đo (diopter = 1/mét)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, f.diopters)
        }
    }
    if (ctl.manualExposure != null && caps.manualSensor) {
        val m = ctl.manualExposure
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, m.exposureNs)             // clamp theo expRange và ≤ 1e9/minFps
        b.set(CaptureRequest.SENSOR_SENSITIVITY, m.iso)                      // clamp theo isoRange
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(m.exposureNs, 33_333_333L))
    }
    regions?.let {                                   // chỉ khi maxAfRegions/maxAeRegions > 0
        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it))
        b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it))
    }
    return b.build()
}
```

Trước khi set, mọi giá trị đều phải nằm trong danh sách hỗ trợ (từ probe). Không có thì bỏ qua key đó và ghi log.

**Chọn dải FPS (`core/FpsRangePicker`, thuần Kotlin, có unit test):**
- `NIGHT`: trong các dải có `upper == 30`, chọn dải có `lower` nhỏ nhất nhưng `>= minFpsFloor` (mặc định 15).
  Không có thì chọn dải `upper == 30` có `lower` nhỏ nhất.
- `STANDARD`: `[30,30]` nếu có, không thì dải `upper == 30` có `lower` lớn nhất.

## 6. Chạm để lấy nét rồi khóa bằng khoảng cách

Trong session preview:

1. Đổi điểm chạm (nx, ny trong khoảng 0..1 của TextureView, máy cầm dọc) sang tọa độ cảm biến.
   Đây là hàm thuần trong `core/Calc`, có unit test, xử lý đủ 4 góc sensorOrientation.
   Với 90° (camera sau Pixel): `sx = ny`, `sy = 1 - nx`.
   Dùng `MeteringRectangle` cạnh bằng 10% chiều ngắn của active array, weight 1000, clamp trong active array.
2. Gửi một `capture()` gồm regions, `CONTROL_AF_MODE_AUTO` và `CONTROL_AF_TRIGGER_START`.
   Sau đó `setRepeatingRequest` với `AF_MODE_AUTO` + `AF_TRIGGER_IDLE`.
3. Trong `captureCallback.onCaptureCompleted`: khi `CONTROL_AF_STATE` là `FOCUSED_LOCKED` hoặc
   `NOT_FOCUSED_LOCKED` (timeout 3 s), đọc `result[LENS_FOCUS_DISTANCE]` rồi set
   `ctl.focus = Focus.Locked(d)` và hiện "Đã khóa nét ≈ X m".
   Chỉ ghi "≈" và số mét khi `focusCalib != UNCALIBRATED`.
4. Nút "∞": `Focus.Locked(0f)`. Nút "Tự động": `Focus.Continuous`.
   Thanh trượt thủ công đi từ 0 tới `minFocusDiopters`.
5. Phiên quay dùng đúng `ctl.focus` đó. Đây là lý do khóa nét giữ nguyên qua lần chuyển session.
   Nếu `minFocusDiopters == 0` (fixed focus) thì ẩn mọi nút về nét.

## 7. Đo FPS thật

Trong `captureCallback.onCaptureCompleted` của session quay: đếm frame và lấy `SENSOR_TIMESTAMP`.
Cứ 10 s tính `fps = frames / Δt`. Ghi kèm `SENSOR_EXPOSURE_TIME` và `SENSOR_SENSITIVITY` trung bình
vào SessionLog rồi hiện trên màn hình đang quay. Giữ callback thật nhẹ, không cấp phát object mỗi frame.

## 8. Orientation hint

```kotlin
// displayRotationDeg = windowManager.defaultDisplay.rotation * 90 (0, 90, 180, 270)
fun orientationHint(sensorOrientation: Int, displayRotationDeg: Int): Int =   // camera sau
    ((sensorOrientation - displayRotationDeg) % 360 + 360) % 360
```

Cầm dọc (rotation 0) thì hint 90, ra video dọc. Xoay ngang thì video ngang.
(Bản đầu của tài liệu này ghi công thức cộng; đã sửa thành trừ theo công thức chuẩn của Camera2. Code trong `core/Calc.kt` là nguồn đúng.)
Có tùy chọn ép "Luôn ngang" / "Luôn dọc".

## 9. MediaStore cho segment

```kotlin
val values = ContentValues().apply {
    put(MediaStore.Video.Media.DISPLAY_NAME, "NC_${stamp}_part${"%03d".format(i)}.mp4")
    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NightCam")
    put(MediaStore.Video.Media.IS_PENDING, 1)
}
val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
val pfd = resolver.openFileDescriptor(uri, "rw")!!
// finalize:
pfd.close(); resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
```

Quét phục hồi lúc app khởi động:
- API 30+: `Bundle` với `MediaStore.QUERY_ARG_MATCH_PENDING = MATCH_INCLUDE`. API 29: `MediaStore.setIncludePending(uri)`.
- Lọc `RELATIVE_PATH = 'Movies/NightCam/'` (giá trị lưu có dấu `/` cuối) và `IS_PENDING = 1`.
- Mỗi file: `SIZE == 0` thì xóa. `MediaMetadataRetriever` đọc được `METADATA_KEY_DURATION` thì un-pend.
  Còn lại đổi tên thêm `_BROKEN` rồi un-pend để người dùng tự quyết.
- **Không** quét khi đang có phiên quay.

App không cần quyền storage để ghi vào MediaStore của chính nó (API 29+).

## 10. Foreground service, wake lock, tối ưu pin

```kotlin
// Activity, trong click handler (app đang hiện):
ContextCompat.startForegroundService(ctx, Intent(ctx, RecordingService::class.java).setAction(ACTION_START))

// Service.onStartCommand, trong vòng 5 s:
val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
    (if (cfg.audio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)

val wl = getSystemService(PowerManager::class.java)
    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NightCam:recording")
wl.setReferenceCounted(false); wl.acquire(12 * 60 * 60 * 1000L)
// release trong finally của mọi đường kết thúc

// Tối ưu pin (hỏi 1 lần, có nút trong Cài đặt):
if (!pm.isIgnoringBatteryOptimizations(packageName))
    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
```

- Kênh thông báo `IMPORTANCE_LOW`, `setOngoing(true)`, `setOnlyAlertOnce(true)`, cập nhật mỗi 10 s.
  Nội dung dạng `01:23:45 · part 3 · 2,1 GB · 38°C`. Có action **Dừng**: PendingIntent tới service với `ACTION_STOP`.
- Không bao giờ start service quay từ nền (boot, alarm). Android 12 cấm việc này.

## 11. Sức khỏe phiên (HealthMonitor + Watchdog), chạy 10–30 s một lần trên thread camera

- Kích thước file: `Os.fstat(pfd.fileDescriptor).st_size`. 60 s không tăng thì `restartPipeline("stalled")`.
  Tối đa 3 lần/giờ, quá thì dừng với lý do rõ ràng.
- Nhiệt: `pm.addThermalStatusListener(exec) { s -> ... }` (API 29).
  `THERMAL_STATUS_SEVERE` thì đặt cờ hạ chất lượng cho lần restart/segment kế tiếp (nếu bật).
  `CRITICAL` trở lên thì dừng an toàn.
  Nhiệt pin để hiển thị: sticky `ACTION_BATTERY_CHANGED`, `EXTRA_TEMPERATURE / 10f`.
- Pin: `EXTRA_LEVEL/EXTRA_SCALE`, không sạc (`EXTRA_PLUGGED == 0`) và dưới 5% thì dừng.
- Dung lượng trống: `StatFs(Environment.getExternalStorageDirectory().path).availableBytes`,
  dưới `reserveBytes` thì dừng. "Còn quay được" = `(free - reserve) / (tổng bps / 8)`.

## 12. Các lỗi hay gặp, đọc trước khi debug

| Triệu chứng | Nguyên nhân thường gặp |
|---|---|
| File 0 byte / `stop failed` | Gọi `start()` trước khi session có repeating request; hoặc chưa có frame nào |
| `IllegalStateException` khi setX | Sai thứ tự gọi MediaRecorder (mục 2) |
| Khung đen | Công tắc "Camera access" đang tắt; hoặc surface preview chưa set buffer size |
| `ERROR_CAMERA_IN_USE` / `onDisconnected` | App khác giành camera. Thử lại 2 lần, cách nhau 2 s, rồi dừng |
| Hình kéo giãn | TextureView không đúng tỷ lệ 9:16 |
| Video quay ngược | Sai orientation hint (mục 8) |
| Mất segment khi chuyển | Không gọi `setNextOutputFile` kịp ở 802, hoặc đóng pfd cũ trước 803 |
| FPS tụt dưới mức chọn ở chế độ Standard | NR HIGH_QUALITY / 4K + HEVC quá tải. Hiện FPS đo được, gợi ý hạ cấu hình |
