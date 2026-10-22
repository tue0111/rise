# XSCamera 4.0.5: kết quả dịch ngược bằng JADX

Công cụ: JADX 1.5.1 headless (cũng là thứ REA, github.com/morluto/rea, dùng cho APK Android).
File: `me.jagar.xscamera2.apk` trong XAPK có SHA-256 `35f48d2e…f9e42`.
Mã đã dịch ngược **không** được đưa vào repo; tài liệu này chỉ ghi lại cách app hoạt động.

## Luồng quay (CameraService + lambda `o00OOOO0` case 21)

1. `onStartCommand`: tạo kênh thông báo tên "SpyCamera", `startForeground` **không có foregroundServiceType**,
   wake lock PARTIAL có timeout cố định.
2. Thêm một **cửa sổ nổi** (`SYSTEM_ALERT_WINDOW`, `TYPE_APPLICATION_OVERLAY` = 2038) chứa `PreviewView`:
   cỡ 35/50/75 dp, hoặc **1×1 pixel** ở các chế độ "ẩn" (`spyModeState` 1–3). Sau đó tự mở màn hình Home.
3. CameraX: `ProcessCameraProvider.bindToLifecycle(service, selector, UseCaseGroup[Preview, VideoCapture, (OverlayEffect timestamp)])`.
   Preview luôn được bind vào `PreviewView` trong cửa sổ nổi.
4. Recorder: `QualitySelector` theo **chỉ số** `videoQuality` trong danh sách quality máy hỗ trợ
   (thứ tự UHD, FHD, HD, SD, HIGHEST, LOWEST). `bitrateMode == 0` đặt 45/10/6/3 Mbps; chế độ khác để CameraX tự chọn.
   Executor `newSingleThreadExecutor()`. Không chọn codec (CameraX 1.6 mặc định H.264).
5. Output: `MediaStoreOutputOptions` (chỉ có DISPLAY_NAME, không đặt thư mục) hoặc `getFilesDir()/DCIM/XSCamera`.
   **Một file duy nhất**, không chia đoạn.
6. Điều khiển ảnh: chỉ có flash (torch) và lấy nét một lần ở tâm khi bắt đầu.
   **Không** có dải FPS, khóa nét, EV, khử nhiễu, chống nhấp nháy hay HEVC.

## Hệ quả cho NightCam

- XSCamera không có "bí quyết" xử lý ảnh nào. NightCam (Camera2 + MediaRecorder) đã có nhiều điều khiển hơn.
- Cửa sổ nổi và preview 1×1 là cách XSCamera giữ preview chạy khi app ở nền. NightCam không cần cách này,
  vì foreground service loại `camera` được phép giữ camera khi tắt màn hình, và phiên quay chỉ có surface của MediaRecorder.
- NightCam **không** sao chép các tính năng che giấu (preview 1×1, đổi tên/biểu tượng thông báo, tự nhảy về Home):
  app luôn hiện thông báo đang quay.
- Nếu Camera2 gặp lỗi riêng trên Pixel 3 XL, phương án dự phòng là chuyển phần ghi sang CameraX
  `Preview + VideoCapture` bind vào lifecycle của service, giống XSCamera, chấp nhận mất HEVC và xoay đoạn liền mạch.
