# NightCam: hướng dẫn cho agent lập trình

App Android quay video dài khi tắt màn hình, tối ưu quay đêm, cho **Google Pixel 3 XL (Android 12, API 31)**.
Kotlin, Views + ViewBinding, CameraX (camera-video, camera-view) với Camera2Interop cho điều khiển ảnh. Không dùng Compose hay DI framework.
Từ bản 0.10.0 lõi ghi hình là CameraX (xem PLAN mục 3.1); CAMERA2_NOTES chỉ còn giá trị tham khảo cho các key CaptureRequest.

## Đọc trước khi làm (theo thứ tự)
1. `docs/PLAN.md`: mục tiêu và các quyết định đã chốt. Không bàn lại các quyết định này.
2. `docs/TASKS.md`: lấy **task chưa đánh dấu đầu tiên**, chỉ làm task đó.
3. `docs/CAMERA2_NOTES.md`: công thức code. Làm đúng thứ tự gọi API trong đó.

## Dựng môi trường (cloud session mới)
```bash
source nightcam/scripts/setup-env.sh      # cài Android SDK + mirror Maven (Maven Central hay trả 429)
cd nightcam && ./gradlew assembleDebug testDebugUnitTest
```
APK ra ở `nightcam/app/build/outputs/apk/debug/app-debug.apk`.

## Quy tắc cứng
- **Không thêm quyền `INTERNET`** hay bất kỳ thư viện quảng cáo, analytics, Firebase hay crash-reporting nào.
- **Không nâng `minSdk` quá 31.** `targetSdk`/`compileSdk` giữ 35.
- Không đổi keystore `keystore/nightcam-sideload.jks` hay `applicationId`: đổi là mất khả năng cài đè.
- Chỉ thêm dependency từ `androidx.*`, `com.google.android.material`, `org.jetbrains.kotlinx` và `junit`
  cho test. Ghi rõ phiên bản cụ thể, không dùng `+`.
- Logic thuần (tính toán, chọn cấu hình) đặt trong `core/`, không import `android.*`, phải có unit test.
- Camera và MediaRecorder chỉ chạy trên thread `nc-camera` và chỉ do `RecordingService` sở hữu.
- Cài đặt lưu bằng id dạng chuỗi, không lưu chỉ số.
- Không nuốt exception im lặng: mọi `catch` đều ghi vào SessionLog (khi đã có, từ T9; trước đó dùng `Log.w("NightCam", …)`).
- Comment và chữ hiển thị trên UI viết tiếng Việt. Tên biến, class viết tiếng Anh.

## Quy trình mỗi task
1. Đọc task và các mục CAMERA2_NOTES liên quan.
2. Viết code và unit test cho phần `core/`.
3. Chạy `./gradlew assembleDebug testDebugUnitTest`. Đỏ thì sửa tới khi xanh, **không** xóa hay skip test.
4. Đánh `[x]` cho task trong `docs/TASKS.md`, thêm dòng ghi chú nếu cần.
5. Commit `T<n>: <tóm tắt>` rồi push lên branch được giao. CI (`NightCam APK`) build APK thành artifact.
6. Báo người dùng: đã làm gì, gửi file APK (nếu công cụ cho phép gửi file) và danh sách
   "Kiểm tra máy" của task để họ thử trên Pixel.

## Khi bế tắc
Code đúng ghi chú mà vẫn lỗi trên máy: thêm log chi tiết quanh chỗ nghi ngờ, build bản debug,
nhờ người dùng quay thử rồi gửi file `Download/NightCam/session_*.txt`. Không đoán mò, không viết lại kiến trúc.
