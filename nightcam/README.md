# NightCam

App quay video dài trên Android: tắt màn hình vẫn quay, không quảng cáo, không cần mạng, tối ưu cho quay đêm.
Viết cho **Google Pixel 3 XL (Android 12, API 31)**; yêu cầu tối thiểu Android 10 (API 29).

## Cài đặt
1. Tải `app-release.apk` (hoặc artifact `NightCam-*` trong tab Actions của repo).
2. Trên máy: **Cài đặt → Bảo mật → Cho phép cài ứng dụng không rõ nguồn gốc** (tùy phiên bản Android, có thể nằm trong Quyền đặc biệt của trình quản lý file).
3. Mở file APK và cài. Cấp quyền Camera, Micro và Thông báo khi được hỏi.
4. Khi được nhắc, cho phép NightCam chạy không giới hạn pin để máy không dừng khi quay lâu.

Bản cài là key tự ký. Muốn cài đè lên bản sau thì giữ nguyên key; không cần lo về Play Store.

## Cách dùng
- **Quay / Dừng:** bấm nút lớn. Khi đang quay, **giữ nút Dừng 1 giây** mới dừng, tránh chạm nhầm.
- **Tắt màn hình:** bấm nút nguồn như bình thường. Quay vẫn tiếp tục.
- **Lấy nét:** chạm vào khung hình. Sau khi nét đã khóa, nhãn sẽ hiện khoảng cách (nếu máy hỗ trợ).
  - **Tự động** để máy bám nét liên tục.
  - **∞** để khóa vô cực (cảnh ngoài trời, xa).
  - Thanh trượt khóa nét tay (chỉ hiện khi máy hỗ trợ).
- **EV −/+:** chỉnh độ sáng tổng thể.
- **Khóa sáng/màu:** khóa phơi sáng và cân bằng trắng khi cảnh đã ổn định, để tránh màu nhảy.
- **Preset:** Đêm cố định (mặc định), Đêm có chuyển động, Ban ngày dài, Chi tiết cao (4K).
- **Cài đặt:** độ phân giải, codec, bitrate, chế độ FPS, khử nhiễu, hướng video, độ dài đoạn, dung lượng dự phòng. Thay đổi áp dụng cho lần quay kế tiếp.

## Mẹo quay đêm
- Ánh sáng quan trọng nhất. Một đèn LED ấm, mờ đặt gần chủ thể giúp hơn mọi thiết lập phần mềm.
- Đèn hồng ngoại không có tác dụng: camera điện thoại có kính lọc IR.
- Chế độ **Đêm** cho phép máy phơi sáng lâu hơn khi tối, đổi lại chuyển động nhòe hơn.
- Quay ở 1080p thường đẹp hơn 4K trong điều kiện thiếu sáng.
- Cắm sạc khi quay lâu. Để máy thoáng, không úp mặt xuống.

## Dung lượng
Ước lượng: khoảng **0,45 GB mỗi giờ cho mỗi 1 Mbps**. Ví dụ 1080p HEVC 14 Mbps ≈ 6,4 GB/giờ; 4K 40 Mbps ≈ 18 GB/giờ.
Màn hình chính luôn hiện số giờ còn quay được theo dung lượng trống thực tế.

## Khi có lỗi
Mỗi phiên quay ghi một file log. Khi phiên kết thúc, log được lưu vào:

    Download/NightCam/session_<thời điểm>.txt

Log chứa cấu hình, khả năng máy, từng đoạn, số liệu FPS, pin, nhiệt và lý do dừng. **Không** chứa nội dung hình hay tiếng.
Gửi file này khi cần hỗ trợ.

Nếu app chết giữa chừng, lần mở tiếp theo sẽ phục hồi các đoạn còn dùng được. Đoạn lỗi được đổi tên thành `_BROKEN` để bạn tự quyết định.

## Dành cho người phát triển
- Hướng dẫn cho agent: `CLAUDE.md`. Kế hoạch: `docs/PLAN.md`. Công việc: `docs/TASKS.md`. Ghi chú Camera2: `docs/CAMERA2_NOTES.md`.
- Build: `source scripts/setup-env.sh && ./gradlew assembleDebug testDebugUnitTest`.
- Logic thuần nằm trong `core/` và có unit test. Phần Camera2 và MediaRecorder cần kiểm tra trên máy thật.
