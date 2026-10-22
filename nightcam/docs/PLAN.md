# NightCam — Kế hoạch tổng thể

App quay video dài, tắt màn hình vẫn quay, không quảng cáo, tối ưu quay đêm.
Máy đích: **Google Pixel 3 XL**. Tài liệu này là "vì sao" và "cái gì";
`docs/TASKS.md` là "làm theo thứ tự nào"; `docs/CAMERA2_NOTES.md` là "viết code thế nào".

---

## 1. Bối cảnh: vì sao không sửa XSCamera mà viết app mới

Đã tự mở `XSCamera_+Record+Privately_4.0.5_APKPure.xapk`
(SHA-256 `35f48d2e…f9e42`, khớp báo cáo ChatGPT) bằng `aapt2` + `dexdump`:

| Phát hiện | Bằng chứng | Hệ quả |
|---|---|---|
| `minSdkVersion 32` (Android 12L) | `aapt2 dump badging` | **Không cài được lên Pixel 3 XL** (máy dừng ở Android 12 = API 31) |
| 8+ mạng quảng cáo: AppLovin, ByteDance/Pangle, Bigo, Mintegral, ironSource, Unity, Vungle, InMobi, Meta, Fyber | Component trong manifest | Không gỡ sạch được bằng cách vá binary |
| Wrapper bảo vệ `com.pairip.application.Application` | Manifest | Ký lại APK gần như chắc chắn hỏng app |
| Bitrate "Recommended" = 45 / 10 / 6 / 3 Mbps (UHD/FHD/HD/SD) | Hằng `0x02aea540`, `0x989680`, `0x5b8d80`, `0x2dc6c0` sau `getInt("bitrateMode")` | 1080p chỉ 10 Mbps: thiếu cho cảnh đêm nhiều nhiễu |
| Chọn quality bằng **chỉ số** (`getInt("videoQuality")`) | DEX | Đổi camera là có thể lệch chất lượng. App mới lưu bằng id dạng chuỗi |
| Lấy nét 1 lần ở tâm, tự hủy sau 3 s | `FocusMeteringAction` + 3000 | Cảnh tối dễ hunting. App mới khóa nét thật bằng khoảng cách |
| Chế độ lưu riêng ghi vào `getFilesDir()/DCIM` + `allowBackup=false` | DEX + manifest | Gỡ app là mất video. App mới ghi thẳng vào thư viện (MediaStore) |

Kết luận: viết app tối giản, mã nguồn mở hoàn toàn, **không có quyền INTERNET**,
nên về mặt kỹ thuật app không thể hiện quảng cáo hay gửi dữ liệu đi.

## 2. Ràng buộc của Pixel 3 XL

Các thông số dưới là **dự kiến**. App phải tự đọc lại lúc chạy (task T2), không hard-code.

- Android 12 (API 31) là bản cuối → `minSdk = 29`, **không được nâng quá 31**.
- Snapdragon 845. Có encoder phần cứng H.264 và HEVC (H.265) của Qualcomm.
- Camera sau: Sony IMX363 12,2 MP, điểm ảnh 1,4 µm, f/1.8, có OIS. Camera2 mức FULL hoặc LEVEL_3, có MANUAL_SENSOR.
  Quay được 4K30 và 1080p30/60.
- App bên thứ ba **không** dùng được HDR+/Night Sight của Google cho video.
  Low Light Boost cần Android 15, máy này không có.
- Pin 3430 mAh, máy đời 2018 nên pin gần như chắc đã chai. Quay qua đêm nên **cắm sạc**,
  để máy thoáng, không ốp và không úp mặt xuống.
- Android 12 có công tắc "Camera access" trong Quick Settings. Nếu tắt, camera trả về khung đen.

## 3. Quyết định kỹ thuật chính

### 3.1 Camera2 + MediaRecorder, không dùng CameraX

| Tiêu chí | CameraX 1.4 (ổn định) | **Camera2 + MediaRecorder** |
|---|---|---|
| HEVC | Không (`setVideoMimeType` chỉ có từ 1.7) | `setVideoEncoder(HEVC)` |
| Chia file gần liền mạch | Phải stop/start (có khoảng hở) | `setMaxFileSize` + `setNextOutputFile` (API 26) |
| Dải FPS cho đêm, khóa nét bằng khoảng cách, NR, chống nhấp nháy đèn, OIS/EIS | Phải qua Camera2Interop, dễ đụng độ | Điều khiển trực tiếp |
| Lượng code | Ít hơn | Nhiều hơn, nhưng có công thức sẵn ở CAMERA2_NOTES |

MediaRecorder dùng encoder phần cứng và tự muxer MP4. Không cần tự viết MediaCodec/MediaMuxer.

### 3.2 Khi đang quay thì không có preview

- **Trước khi quay:** session preview (TextureView) để căn khung, chạm lấy nét, chỉnh EV.
- **Bấm Quay:** đóng session preview và mở session mới **chỉ có surface của MediaRecorder**.
  Mất khoảng 0,5 s, nhưng camera không phải đẩy frame ra màn hình và GPU không phải vẽ,
  nên tiết kiệm pin nhất và tắt màn hình không làm hỏng gì.
- Khi đang quay, màn hình chỉ hiện chữ trên nền đen (OLED nên gần như không tốn điện):
  thời gian, segment, dung lượng, nhiệt độ, FPS đo được.
- "Xem nhanh 10 s" khi đang quay là tính năng tùy chọn (task T11).

### 3.3 Quay đêm: dùng đúng những gì phần mềm làm được

Thứ tự ảnh hưởng tới chất lượng đêm, từ lớn tới nhỏ:

1. **Ánh sáng thật.** Đèn LED ấm, mờ. Đèn IR vô dụng vì camera điện thoại có kính lọc IR.
2. **Dải FPS linh hoạt `[15,30]`** (profile Night). AE được phép phơi sáng tới 1/15 s
   khi tối, tức thu gấp đôi ánh sáng so với khóa cứng 30 fps. Đổi lại vật chuyển động nhòe hơn
   và video là VFR. Đây là nút phần mềm hiệu quả nhất.
3. **Khóa nét bằng khoảng cách** (`AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` đo được lúc chạm),
   để nét không chạy lung tung trong tối.
4. **Bitrate đủ:** 1080p HEVC 14 Mbps hoặc H.264 20 Mbps. Nhiễu đêm ngốn bit.
5. **Chống nhấp nháy đèn 50 Hz** (lưới điện Việt Nam), AWB lock khi đèn đã ổn định.
6. **NR:** mặc định FAST. HIGH_QUALITY là tùy chọn, có hiển thị FPS đo được để kiểm tra.
7. Mặc định tắt EIS (máy đặt trên chân thì EIS chỉ crop và tốn điện). OIS để bật.
8. Ban đêm ưu tiên 1080p hơn 4K: hình đọc full cảm biến rồi scale xuống sẽ ít nhiễu hơn.

**Không làm:** sharpen/AI super-resolution realtime, timestamp vẽ lên hình (tốn GPU),
nhân đôi frame để giả FPS.

### 3.4 Lưu trữ an toàn

- Ghi vào `Movies/NightCam/` qua MediaStore với `IS_PENDING=1`. Segment nào xong thì chuyển sang `IS_PENDING=0`.
- Segment mặc định 10 phút (tính theo byte = bitrate × 600 s), tối đa 3,5 GB mỗi file (MP4 của MediaRecorder giới hạn 4 GB).
- App chết giữa chừng thì chỉ mất segment đang ghi. Mở lại app sẽ quét các file pending còn sót:
  phát được thì giữ, 0 byte thì xóa, còn lại đổi tên `_BROKEN`.
- Dừng an toàn khi còn dưới 1 GB trống (cấu hình được). Không bao giờ tự xóa video cũ.
- Cài đặt lưu bằng **id dạng chuỗi** (`"1080p"`, `"hevc"`), không dùng chỉ số.

### 3.5 Pin, nhiệt và độ bền phiên quay

- Foreground service loại `camera` (+ `microphone` nếu thu âm), thông báo có nút Dừng.
- `PARTIAL_WAKE_LOCK` giữ suốt phiên, timeout 12 h, gia hạn mỗi lần sang segment, nhả ở mọi đường thoát.
- Lần đầu chạy, nhắc người dùng tắt tối ưu pin cho app (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- Watchdog: 60 s mà file không lớn thêm thì mở lại camera/segment, tối đa 3 lần/giờ, quá thì dừng và báo lỗi.
- Nhiệt (`PowerManager.addThermalStatusListener`):
  SEVERE thì segment kế tiếp hạ chất lượng (4K→1080p, rồi hạ bitrate) nếu người dùng cho phép; CRITICAL thì đóng file và dừng.
- Pin dưới 5 % và không sạc thì đóng file và dừng.

## 4. Kiến trúc mã

```
com.tue.nightcam
├── MainActivity            UI: preview, điều khiển, màn hình đang quay
├── SettingsActivity        cài đặt (hoặc bottom sheet)
├── core/                   LOGIC THUẦN KOTLIN, KHÔNG import android.* → unit test được
│   ├── RecordingConfig     data class + id dạng chuỗi + validate
│   ├── Presets             preset mặc định cho Pixel 3 XL
│   ├── Calc                bitrate→GB/giờ, kích thước segment, giờ còn lại, orientation hint
│   └── FpsRangePicker      chọn dải AE FPS theo profile
├── probe/
│   └── CapabilityProbe     đọc CameraCharacteristics + MediaCodecList → DeviceCaps
├── record/
│   ├── RecordingService    LifecycleService, chủ duy nhất của phiên quay, state machine
│   ├── CameraController    mở camera, tạo session, build CaptureRequest
│   ├── RecorderController  MediaRecorder + xoay segment
│   ├── SegmentStore        MediaStore insert/pending/finalize/recover
│   ├── Watchdog            kiểm tra file có lớn dần không
│   └── HealthMonitor       nhiệt, pin, dung lượng trống
└── log/
    └── SessionLog          log text → Download/NightCam/session_*.txt
```

**State machine** của `RecordingService`:
`IDLE → PREPARING → RECORDING ⇄ ROTATING → FINALIZING → IDLE`, cộng nhánh `ERROR`.

- `START` khi không ở `IDLE` thì bỏ qua. `STOP` chỉ có hiệu lực một lần.
- UI theo dõi state qua `StateFlow` (bound service, `LocalBinder`).
- Activity bị hủy hay màn hình tắt **không bao giờ** làm dừng phiên quay.

## 5. Preset mặc định (Pixel 3 XL)

| Preset | Kích thước | Codec / bitrate | AE FPS | Nét | Ghi chú |
|---|---|---|---|---|---|
| **Đêm cố định** (mặc định) | 1920×1080 | HEVC 14 Mbps | [15,30] | Khóa | Antibanding 50 Hz, NR FAST, EIS off |
| Đêm có chuyển động | 1920×1080 | HEVC 16 Mbps | [24,30] hoặc [30,30] | Liên tục | Bớt nhòe, tối hơn |
| Ban ngày dài | 1920×1080 | HEVC 10 Mbps | [30,30] | Liên tục | Tiết kiệm dung lượng |
| Chi tiết cao | 3840×2160 | HEVC 40 Mbps | [30,30] | Khóa | Nóng, tốn chỗ; chỉ dùng khi đủ sáng |

Không có HEVC phần cứng thì tự chuyển H.264 với bitrate ×1,4.
Audio: AAC 128 kbps mono 48 kHz, có thể tắt.

Dung lượng: **GB/giờ ≈ 0,45 × Mbps tổng**.
Ví dụ 14 + 0,128 Mbps ≈ 6,4 GB/giờ, tức khoảng 51 GB cho 8 giờ.
Pixel 3 XL bản 64 GB nên quay qua đêm cần dọn bộ nhớ hoặc dùng preset 10 Mbps (khoảng 4,5 GB/giờ).

## 6. Ngoài phạm vi

Streaming, upload, cloud, quảng cáo, analytics, AI realtime, HDR 10-bit, camera trước,
tự khởi động khi bật máy, mã hóa file.

## 7. Kỳ vọng thực tế

- Quay dài tắt màn hình, không quảng cáo, file không hỏng hàng loạt: **làm được chắc chắn**.
- Đêm: hình sáng và đỡ hunting hơn XSCamera nhờ dải FPS, khóa nét và bitrate.
  Nhưng phòng gần tối hẳn thì không phần mềm nào cứu được: cần thêm đèn.
- 4K: có thật (cảm biến 12 MP đọc ra 4K), nhưng ban đêm thường không hơn 1080p.
