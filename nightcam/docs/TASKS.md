# NightCam: danh sách task

Làm **từ trên xuống, mỗi lần một task**. Xong task nào thì đánh `[x]` vào đó,
ghi một dòng "Ghi chú" nếu có gì đáng nói, rồi commit với message `T<n>: <tóm tắt>`.

"Kiểm tra máy" là việc dành cho người dùng làm trên Pixel 3 XL. Agent không tự làm được phần này,
chỉ cần ghi lại danh sách đó trong câu trả lời khi giao APK.

Mỗi task chỉ được coi là xong khi `./gradlew assembleDebug testDebugUnitTest` chạy thành công.

---

## [x] T0: Scaffold và build
Gradle 8.14.3, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 29, key ký cố định,
workflow CI `.github/workflows/nightcam-apk.yml`. Đã build thử: APK debug cài được, không có INTERNET.

## [x] T1: Lớp `core/` thuần Kotlin + unit test
Ghi chú: 24 unit test (RecordingConfig, Calc, FpsRangePicker) chạy xanh.
Tạo `app/src/main/java/com/tue/nightcam/core/`, **không import `android.*`**:
- `RecordingConfig` (data class): `resolutionId` ("2160p"|"1080p"|"720p"), `codecId` ("hevc"|"avc"),
  `videoMbps`, `profileId` ("night"|"standard"), `minFpsFloor`, `audio`, `segmentMinutes`, `reserveGb`,
  `nrId` ("fast"|"hq"), `eis`, `ois`, `orientationId` ("auto"|"landscape"|"portrait"), `thermalDowngrade`.
  Có `toMap()/fromMap()` để lưu vào SharedPreferences **bằng key dạng chuỗi**. Gặp id lạ thì dùng giá trị mặc định.
- `Presets`: 4 preset ở PLAN mục 5, mặc định là "Đêm cố định".
- `Calc`: `gbPerHour(totalMbps)`, `segmentBytes(videoBps, audioBps, seconds)` (clamp 3,5e9),
  `hoursRemaining(freeBytes, reserveBytes, totalBps)`, `orientationHint(sensorDeg, deviceDeg)`,
  `roundToRightAngle(deg)`, `touchToSensor(nx, ny, sensorOrientation)` (trả về điểm 0..1 trong hệ tọa độ cảm biến),
  `avcFallbackMbps(hevcMbps) = hevcMbps * 1.4`.
- `FpsRangePicker.pick(ranges: List<IntRange>, profile, minFloor): IntRange` (CAMERA2_NOTES mục 5).
- Thêm `testImplementation("junit:junit:4.13.2")`. Unit test cho mọi hàm trên, gồm các ca biên:
  danh sách FPS rỗng hoặc không có dải upper=30, sensor 0/90/180/270, dung lượng trống nhỏ hơn reserve.

## [x] T2: CapabilityProbe + màn "Thông tin máy"
Ghi chú: ConfigValidator có 7 test; hộp thoại Thông tin máy build được. Chưa chạy trên Pixel thật.
- `probe/CapabilityProbe.kt` đọc mọi thứ ở CAMERA2_NOTES mục 1, trả về `DeviceCaps` (data class) + `toJson()`
  (dùng `org.json.JSONObject`).
- `RecordingConfig.validate(caps)`: tổ hợp không hỗ trợ thì trả về config đã sửa kèm danh sách cảnh báo,
  ví dụ "Không có HEVC phần cứng, chuyển H.264 20 Mbps".
- Nút "Thông tin máy" trong UI: hiện JSON (TextView có cuộn, chữ monospace) và nút Sao chép.

## [x] T3: MVP quay nền (bản đầu tiên dùng được thật)
Ghi chú: đã build, không có test tự động cho phần camera. Chưa chạy trên Pixel; kiểm tra máy còn chờ người dùng. Đã sửa: bấm Dừng khi camera đang mở thì đóng camera ngay; không để camera mở sau khi dừng.
- `RecordingService` (LifecycleService + `LocalBinder` + `StateFlow<RecState>`), có `HandlerThread("nc-camera")`.
- `CameraController` + `RecorderController`: một file duy nhất, chưa chia segment,
  session **chỉ có surface recorder** (CAMERA2_NOTES mục 2, 4, 5), dùng giá trị từ config/preset.
- `SegmentStore.create/finalize` (CAMERA2_NOTES mục 9).
- Foreground service + notification có nút Dừng + wake lock (CAMERA2_NOTES mục 10).
- MainActivity khóa dọc: xin quyền CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS (API 33+).
  Có nút Quay/Dừng to, hiện state và thời gian. Activity bind ở onStart, unbind ở onStop.
- **Kiểm tra máy:** quay 10 phút, bấm nút nguồn tắt màn hình ngay sau khi bấm Quay.
  File trong Photos/Files ở `Movies/NightCam` phải phát được đủ 10 phút và có tiếng.

## [x] T4: Chia segment + phục hồi sau crash
Ghi chú: 5 test cho đặt tên và phân loại phục hồi; xoay đoạn qua 802/803, đường dự phòng 801 mở lại camera. Chưa kiểm tra trên máy.
- Xoay segment bằng `setMaxFileSize`/`setNextOutputFile` (CAMERA2_NOTES mục 3), xử lý cả nhánh 801.
- Tên file `NC_yyyyMMdd_HHmmss_partNNN.mp4`, cùng `stamp` cho cả phiên.
- `SegmentStore.recover()` chạy lúc app mở (khi không có phiên quay), kết quả báo bằng một Snackbar.
- **Kiểm tra máy:** cho segment 1 phút, quay 5 phút ra 5 file liền nhau.
  Quay 3 phút rồi vuốt kill app trong Recent Apps (service vẫn chạy thì dùng Force stop trong Settings),
  mở lại app thì các file đã xong vẫn phát được.

## [ ] T5: Preview + lấy nét/phơi sáng
- TextureView tỷ lệ 9:16, `attachPreview/detachPreview` qua binder (CAMERA2_NOTES mục 0 và 4).
- Chạm vào preview để lấy nét/đo sáng tại điểm đó rồi khóa khoảng cách nét (mục 6), có vòng tròn chỉ vị trí.
  Có nút Tự động / ∞ / thanh trượt thủ công.
- EV −/+ theo `evStep`, hiện giá trị EV.
- Bấm Quay: chuyển sang session chỉ có recorder, **giữ nguyên trạng thái nét và EV**.
  Màn hình chuyển sang nền đen hiện thông tin (chi tiết ở T8).
- **Kiểm tra máy:** trong phòng tối, chạm khóa nét vào một vật cách 2 m rồi quay 5 phút.
  Hình không được "thở" nét.

## [ ] T6: Profile Đêm và các điều khiển ảnh
- Áp `FpsRangePicker` theo profile, antibanding 50 Hz, NR FAST/HQ, EIS, OIS, AE lock, AWB lock
  (mục 5). Mọi giá trị đều phải qua kiểm tra hỗ trợ từ `DeviceCaps`.
- Đo FPS thật cùng exposure/ISO trung bình mỗi 10 s (mục 7), đưa vào state và log.
- Phơi sáng thủ công (ẩn trong mục "Nâng cao"): chọn shutter 1/15, 1/25, 1/30, 1/50, 1/60, 1/100 và ISO,
  chỉ hiện khi máy có `manualSensor`.
- **Kiểm tra máy:** quay cùng một cảnh tối 1 phút ở Standard và 1 phút ở Night, so độ sáng và nhiễu.
  Màn hình Night phải hiện FPS dưới 30 khi cảnh đủ tối.

## [ ] T7: Độ bền phiên quay dài
- `Watchdog` + `HealthMonitor` (mục 11): file không lớn thêm, nhiệt, pin yếu, hết chỗ.
- Mất camera (`onDisconnected/onError`): thử mở lại 2 lần, cách nhau 2 s, rồi dừng an toàn.
- Hạ chất lượng khi máy nóng (nếu `thermalDowngrade`): 4K→1080p, rồi hạ bitrate 25%.
  Thay đổi áp dụng ở lần restart pipeline kế tiếp và phải ghi vào log.
- Màn hình nhắc tắt tối ưu pin (hiện một lần, có nút trong Cài đặt).
- Mọi đường kết thúc (dừng tay, lỗi, nóng, pin, hết chỗ, `onDestroy`) đều đi qua **một** hàm `finishSession(reason)`:
  stop recorder → finalize segment → đóng session/camera → nhả wake lock → `stopForeground` → `stopSelf`.
- **Kiểm tra máy:** quay 2 giờ 15 phút tắt màn hình (vượt mốc 2 giờ khiến XSCamera dừng).
  Quay qua đêm khi cắm sạc. Quay tới lúc đầy bộ nhớ (đặt reserve lớn để thử nhanh).

## [ ] T8: Giao diện hoàn chỉnh
- Màn hình chờ: preview, chọn preset (chip), nút nét/EV, nút Cài đặt, nút Thông tin máy,
  dòng "≈ X GB/giờ · còn quay được ~Y giờ".
- Màn hình đang quay: **nền đen tuyệt đối** (`#000000`), chữ xám mờ, cập nhật mỗi giây:
  thời gian, segment, dung lượng đã ghi, FPS đo, nhiệt pin, % pin, giờ còn lại.
  Nút Dừng phải giữ 1 giây mới dừng (tránh chạm nhầm). Dòng nhắc "Có thể bấm nút nguồn tắt màn hình".
- Cài đặt (PreferenceFragmentCompat hoặc màn hình tự làm): mọi trường của `RecordingConfig`.
  Áp dụng cho lần quay sau, không đổi giữa phiên đang chạy.
- Toàn bộ chữ hiển thị bằng tiếng Việt trong `strings.xml`.

## [ ] T9: Log phiên
- `SessionLog`: ghi vào `filesDir/logs/` trong lúc quay (append có buffer, flush mỗi 30 s).
  Kết thúc phiên thì copy sang `Download/NightCam/session_<stamp>.txt` qua `MediaStore.Downloads`.
- Nội dung: DeviceCaps JSON, config đã yêu cầu so với config thực, mọi chuyển state, mỗi segment
  (tên, byte, thời lượng), FPS/exposure/ISO mỗi 10 s, nhiệt, pin, lỗi, lý do dừng. **Không** log nội dung hình hay tiếng.

## [ ] T10: Hoàn thiện và phát hành
- Bản `assembleRelease` (R8) chạy được. Tăng `versionCode`/`versionName`.
- Viết `nightcam/README.md` tiếng Việt cho người dùng: cách tải APK từ GitHub Actions (tab Actions → run → Artifacts),
  cài APK trên Pixel ("Cài ứng dụng không rõ nguồn gốc"), các preset, mẹo quay đêm (PLAN 3.3),
  dung lượng mỗi giờ, cách gửi log khi có lỗi.

## [ ] T11 (tùy chọn): Tiện ích thêm
- "Xem nhanh 10 s" khi đang quay: tạm dựng lại session gồm `[recSurface, previewSurface]`
  rồi quay về `[recSurface]`. Ghi log khoảng hở nếu có.
- Quick Settings tile mở MainActivity và bắt đầu quay luôn (vẫn phải đi qua Activity vì luật FGS của Android 12).
