



**PRODUCT REQUIREMENTS DOCUMENT**

**File Service – Unified File Management**

Microservice Template · Dự án Mytel CRBT


|**Module**|file-service  :8083|
| :- | :- |
|**Phiên bản**|v0.1 – Draft|
|**Trạng thái**|Draft – Chờ Review|
|**Ngày tạo**|19/5/2026|
|**Tech stack**|Spring Boot 3.2 · MinIO · PostgreSQL|
|**Tích hợp**|API Gateway :8080 · rbt-audio-service|


# **1. Problem Statement**
## **1.1 Bối cảnh**
Hệ thống Mytel CRBT cần lưu trữ và phục vụ nhiều loại file khác nhau: ảnh thumbnail, ảnh avatar, file nhạc MP3/WAV (5–10MB), file audio TTS. Hiện tại chưa có một module thống nhất xử lý việc này.

File nhạc có kích thước lớn (5–10MB) nếu đi qua backend sẽ tiêu tốn bandwidth, tăng latency và gây tắc nghẽn. File nhỏ (ảnh < 2MB) thì ngược lại, cần xác thực nghiệp vụ trước khi lưu nên phải đi qua backend.
## **1.2 Vấn đề cần giải quyết**
- Không có một điểm thống nhất để upload/download file cho toàn hệ thống
- File lớn (audio 5–10MB) không thể đi qua backend — gây timeout và tốn tài nguyên
- Thiếu cơ chế kiểm soát loại file, kích thước, và quyền truy cập
- Client cần URL ổn định để preview ảnh và stream audio mà không cần auth mỗi lần

# **2. Goals & Success Metrics**
## **2.1 Mục tiêu**
- Cung cấp 2 luồng upload: backend API (file nhỏ) và presigned URL (file lớn)
- Tất cả file được lưu trên MinIO, tổ chức theo bucket/prefix rõ ràng
- Client có thể preview/stream file qua URL công khai hoặc presigned GET URL
- Module có thể tái sử dụng cho mọi service trong hệ thống template

## **2.2 Success Metrics**

|**KPI**|**Mục tiêu**|**Đo lường**|
| :- | :- | :- |
|Upload file nhỏ (≤ 5MB) qua API|Response < 500ms P95|Liên tục|
|Tạo presigned URL|Response < 100ms P95|Liên tục|
|Upload file lớn qua presigned PUT|Không qua backend – 0 bytes|Mỗi request|
|Tỷ lệ lỗi upload|< 0.5%|Hàng ngày|
|File URL có thể preview ngay sau upload|100% – không delay|Mỗi request|

# **3. Cấu trúc MinIO Buckets**
Tổ chức theo loại nội dung, không theo service — để các service khác nhau có thể dùng chung bucket khi cùng loại file.

|**Bucket**|**Mục đích**|**Access**|**Retention**|**Max file size**|
| :- | :- | :- | :- | :- |
|media-images|Ảnh avatar, thumbnail, banner|Public read|Vĩnh viễn|5 MB|
|media-audio|File nhạc MP3/WAV (generate + DIY source)|Public read|Vĩnh viễn|50 MB|
|media-temp|File upload chờ xử lý (presigned)|Private|24 giờ (auto-delete)|50 MB|
|media-private|File nội bộ, không public|Private + presigned GET|Theo nghiệp vụ|100 MB|

ℹ media-temp: MinIO lifecycle rule tự động xóa sau 24h. Backend confirm upload xong → move sang bucket đích.

# **4. Functional Requirements**
*Ưu tiên MoSCoW: Must / Should / Could / Won't*

## **4.1 Luồng A – Upload qua Backend API (file nhỏ ≤ 5MB)**
ℹ Dùng cho: ảnh avatar, thumbnail, banner, icon. File đi qua backend để validate nghiệp vụ trước.

|**ID**|**Tính năng**|**Mô tả**|**Priority**|**Acceptance Criteria**|
| :- | :- | :- | :- | :- |
|FR-A01|Upload single file|POST /files/upload · multipart/form-data · nhận file + metadata (bucket, prefix)|Must|Trả về { fileKey, url, size, contentType } trong < 500ms|
|FR-A02|Validate file trước upload|Kiểm tra: contentType whitelist, fileSize ≤ giới hạn bucket, filename sanitize|Must|File sai type → 400 với message rõ ràng. File vượt size → 400.|
|FR-A03|Upload multiple files|POST /files/upload/batch · tối đa 10 file/request|Should|Trả array kết quả, file nào lỗi ghi rõ lý do, file khác vẫn upload thành công|
|FR-A04|Tự động tạo thumbnail ảnh|Sau khi upload ảnh → tạo thêm thumbnail 200×200 lưu cùng prefix|Could|File gốc + thumbnail đều có URL riêng, thumbnail suffix \_thumb|
|FR-A05|Xóa file|DELETE /files/{fileKey} · chỉ xóa được file của mình (theo X-Subscriber-Id)|Must|Xóa thành công → 200. File không tồn tại → 404. Không có quyền → 403.|

## **4.2 Luồng B – Upload trực tiếp MinIO qua Presigned URL (file lớn)**
ℹ Dùng cho: file nhạc MP3/WAV 5–10MB. File không đi qua backend. Backend chỉ cấp token và verify sau khi upload xong.

|**ID**|**Tính năng**|**Mô tả**|**Priority**|**Acceptance Criteria**|
| :- | :- | :- | :- | :- |
|FR-B01|Tạo presigned PUT URL|POST /files/presigned-url · nhận { filename, fileSize, contentType } · trả { presignedUrl, fileKey, expiresIn }|Must|Response < 100ms. URL có TTL = 5 phút. fileSize > 50MB → 400.|
|FR-B02|Client upload thẳng MinIO|Client dùng presigned URL để PUT file trực tiếp lên MinIO|Must|Backend không nhận byte nào của file. MinIO trả 200 OK sau upload.|
|FR-B03|Confirm upload hoàn tất|POST /files/confirm · nhận { fileKey } · backend verify file tồn tại trên MinIO bằng statObject()|Must|File tồn tại → 200 + { url, size }. File không tồn tại → 404. Gọi confirm sau 10 phút → 410 Gone.|
|FR-B04|Move file từ temp sang đích|Sau confirm → move từ media-temp sang bucket đích (media-audio)|Must|File ở đúng bucket sau confirm. File trong temp bị xóa.|
|FR-B05|Multipart upload (file > 10MB)|Hỗ trợ tạo presigned URL cho multipart upload với MinIO SDK|Should|File 10–50MB upload thành công qua multipart. Mỗi part ≥ 5MB.|

## **4.3 Luồng C – Phục vụ file (Preview / Stream / Download)**

|**ID**|**Tính năng**|**Mô tả**|**Priority**|**Acceptance Criteria**|
| :- | :- | :- | :- | :- |
|FR-C01|Public URL cho file public|File trong media-images, media-audio có URL trực tiếp từ MinIO. Không cần auth.|Must|URL format: {minio-host}/{bucket}/{fileKey}. Trả về đúng Content-Type.|
|FR-C02|Presigned GET URL cho file private|GET /files/presigned-get/{fileKey} · tạo URL tạm thời TTL = 1 giờ|Must|URL có thể dùng trong 1h. Hết hạn → 403 từ MinIO.|
|FR-C03|Stream audio với Range request|MinIO hỗ trợ HTTP Range header — client có thể seek trong audio mà không download toàn bộ|Must|Trình phát audio trên app có thể seek. Header Accept-Ranges: bytes trả về đúng.|
|FR-C04|Lấy metadata file|GET /files/{fileKey}/info · trả { size, contentType, uploadedAt, uploader }|Should|Response < 100ms từ DB (không gọi MinIO mỗi request).|
|FR-C05|Liệt kê file của user|GET /files?prefix=audio&page=1 · phân trang 20 items/page|Should|Chỉ trả file của X-Subscriber-Id hiện tại. Có totalCount.|

# **5. API Contracts**
## **5.1 Upload qua Backend**

|**Method + Path**|POST /api/files/upload|
| :- | :- |
|**Headers**|Authorization: Bearer <token>  (từ Gateway inject X-Subscriber-Id)|
|**Request**|multipart/form-data: file (binary), bucket (string), prefix (string, optional)|
|**Response 200**|{ "fileKey": "images/uuid.jpg", "url": "https://minio.host/...", "size": 204800, "contentType": "image/jpeg" }|
|**Response 400**|{ "error": "FILE\_TOO\_LARGE", "maxSize": "5MB" }|

## **5.2 Tạo Presigned URL**

|**Method + Path**|POST /api/files/presigned-url|
| :- | :- |
|**Request Body**|{ "filename": "music.mp3", "fileSize": 8388608, "contentType": "audio/mpeg" }|
|**Response 200**|{ "presignedUrl": "https://minio.host/media-temp/uuid?X-Amz-...", "fileKey": "media-temp/uuid\_music.mp3", "expiresIn": 300 }|
|**Response 400**|{ "error": "INVALID\_CONTENT\_TYPE" }  hoặc  { "error": "FILE\_TOO\_LARGE", "maxSize": "50MB" }|

## **5.3 Confirm Upload**

|**Method + Path**|POST /api/files/confirm|
| :- | :- |
|**Request Body**|{ "fileKey": "media-temp/uuid\_music.mp3", "targetBucket": "media-audio" }|
|**Response 200**|{ "fileKey": "media-audio/uuid\_music.mp3", "url": "https://minio.host/media-audio/...", "size": 8388608 }|
|**Response 404**|{ "error": "FILE\_NOT\_FOUND" }  — file chưa upload hoặc đã hết hạn|
|**Response 410**|{ "error": "UPLOAD\_EXPIRED" }  — quá 10 phút kể từ khi tạo presigned URL|

## **5.4 Whitelist Content-Type**

|**Loại file**|**Content-Type cho phép**|**Bucket đích**|**Size tối đa**|
| :- | :- | :- | :- |
|Ảnh|image/jpeg, image/png, image/webp, image/gif|media-images|5 MB|
|Audio|audio/mpeg (MP3), audio/wav, audio/ogg|media-audio|50 MB|
|Video|Không hỗ trợ trong phase này|—|—|
|Document|Không hỗ trợ trong phase này|—|—|

# **6. Database Schema**
Lưu metadata file trong PostgreSQL để tra cứu nhanh mà không gọi MinIO mỗi request.

|**Column**|**Type**|**Nullable**|**Mô tả**|
| :- | :- | :- | :- |
|id|UUID (PK)|No|Primary key|
|file\_key|VARCHAR(500)|No|Key trên MinIO: bucket/prefix/uuid\_filename|
|bucket|VARCHAR(100)|No|Tên bucket MinIO|
|original\_name|VARCHAR(500)|No|Tên file gốc do user upload|
|content\_type|VARCHAR(100)|No|MIME type: audio/mpeg, image/jpeg...|
|size\_bytes|BIGINT|No|Kích thước file tính bằng bytes|
|url|TEXT|No|Public URL hoặc presigned URL (cached)|
|uploader\_id|VARCHAR(100)|No|Subscriber ID từ X-Subscriber-Id header|
|status|VARCHAR(20)|No|PENDING / CONFIRMED / DELETED|
|upload\_type|VARCHAR(20)|No|DIRECT (qua API) / PRESIGNED|
|expires\_at|TIMESTAMP|Yes|Thời điểm hết hạn confirm (chỉ PRESIGNED)|
|created\_at|TIMESTAMP|No|Thời điểm tạo record|
|confirmed\_at|TIMESTAMP|Yes|Thời điểm confirm xong (chỉ PRESIGNED)|

ℹ Index: (uploader\_id, status), (file\_key) UNIQUE, (expires\_at) WHERE status='PENDING'

# **7. Non-Functional Requirements**

|**Danh mục**|**Yêu cầu**|**Metric cụ thể**|
| :- | :- | :- |
|Performance|Upload file nhỏ qua API|< 500ms P95 · < 1s P99|
|Performance|Tạo presigned URL|< 100ms P95|
|Performance|Lấy metadata file|< 50ms P95 (từ DB, không gọi MinIO)|
|Performance|Stream audio với Range request|Time-to-first-byte < 200ms|
|Scalability|Concurrent upload requests|100 concurrent uploads không degradation|
|Storage|Dung lượng MinIO|Monitor hàng tuần · Alert khi > 80%|
|Security|Whitelist Content-Type|Reject tất cả type ngoài whitelist → 400|
|Security|File size validation|Validate cả ở backend và presigned URL TTL|
|Security|Presigned URL không đoán được|Dùng UUID v4 cho fileKey · không expose path đoán được|
|Security|CORS cho presigned PUT|Chỉ cho phép domain Mytel App|
|Availability|Uptime|99\.5% (downtime tối đa ~44h/năm)|
|Cleanup|Auto-delete file PENDING quá hạn|Scheduler chạy mỗi 1h · xóa file expires\_at < now()|

# **8. User Stories & Acceptance Criteria**
## **US-01 – Upload ảnh avatar**
*As a thuê bao Mytel, I want to upload ảnh avatar qua app, so that ảnh của tôi hiển thị trên profile.*

|**#**|**Acceptance Criteria**|
| :- | :- |
|AC1|Upload file JPEG/PNG ≤ 5MB → response < 500ms với URL đầy đủ để hiển thị ngay|
|AC2|Upload file > 5MB → 400 BAD\_REQUEST với thông báo rõ ràng|
|AC3|Upload file .exe hoặc audio/mpeg vào bucket images → 400 INVALID\_CONTENT\_TYPE|
|AC4|URL trả về có thể load ảnh ngay mà không cần auth|

## **US-02 – Upload file nhạc lớn (DIY flow)**
*As a thuê bao Mytel, I want to upload file nhạc 5–10MB trực tiếp lên storage mà không bị timeout, so that trải nghiệm upload nhanh và ổn định.*

|**#**|**Acceptance Criteria**|
| :- | :- |
|AC1|Gọi POST /presigned-url → nhận URL và fileKey trong < 100ms|
|AC2|Client PUT file 8MB thẳng lên MinIO presigned URL → MinIO trả 200 OK|
|AC3|Gọi POST /confirm với fileKey → nhận URL file sau khi được move sang media-audio|
|AC4|Không gọi confirm sau 10 phút → file bị xóa khỏi temp, confirm trả 410 Gone|
|AC5|Backend không nhận byte nào của file trong suốt quá trình upload|

## **US-03 – Stream audio trên app**
*As a thuê bao Mytel, I want to nghe thử nhạc chờ trong app mà không phải chờ download hết file, so that trải nghiệm nghe nhanh và mượt.*

|**#**|**Acceptance Criteria**|
| :- | :- |
|AC1|MinIO trả header Accept-Ranges: bytes cho file audio|
|AC2|App có thể seek đến giây bất kỳ mà không cần load lại toàn bộ file|
|AC3|Time-to-first-byte < 200ms trên kết nối 4G|

# **9. Out of Scope (Phase này KHÔNG làm)**

|**#**|**Không làm**|**Lý do**|
| :- | :- | :- |
|1|Upload video|Chưa có use case · file quá lớn cần CDN riêng|
|2|Upload document (PDF, DOCX...)|Không cần trong CRBT phase 1|
|3|CDN / Edge caching|MinIO đủ dùng cho scale hiện tại|
|4|Virus scan file upload|Nice-to-have · để backlog|
|5|Image resizing tự động (ngoài thumbnail 200×200)|Scope phức tạp · để phase 2|
|6|Audio transcode / format convert|rbt-audio-service đảm nhiệm|
|7|Shared/public gallery cho cộng đồng|Thuộc nghiệp vụ Thư viện Cộng đồng, service khác quản lý|

# **10. Dependencies & Risks**
## **10.1 Dependencies**

|**Phụ thuộc**|**Mô tả**|**Ghi chú**|
| :- | :- | :- |
|MinIO|Object storage · lưu toàn bộ file|Cần config CORS cho presigned PUT từ Mytel App domain|
|API Gateway :8080|Inject X-Subscriber-Id · rate limit upload|Cần thêm route /api/files/\*\* nếu chưa có|
|PostgreSQL|Lưu metadata file|DB riêng: file\_service\_db · không share với service khác|
|rbt-audio-service|Gọi POST /files/presigned-url để upload nhạc DIY|Feign Client lb://file-service|
|Mytel App (FE)|Cần implement PUT presigned URL phía client|FE tự xử lý progress bar và retry khi lỗi mạng|

## **10.2 Risks**

|**Rủi ro**|**Mức độ**|**Giảm thiểu**|
| :- | :- | :- |
|CORS config sai → client không PUT được lên MinIO|Cao|Test CORS từ đầu với domain thật · document config rõ ràng|
|File PENDING tích lũy không xóa → MinIO đầy|Trung bình|Scheduler hourly + MinIO lifecycle rule TTL 24h làm lớp 2|
|Presigned URL bị leak → ai cũng upload được|Thấp|TTL ngắn (5 phút) · UUID không đoán được · 1 URL 1 lần dùng|
|MinIO downtime → toàn bộ file không access được|Thấp|MinIO HA mode (erasure coding) · backup policy|

# **11. Timeline & Milestones**

|**Phase**|**Nội dung**|**Estimate**|**Done when**|
| :- | :- | :- | :- |
|Phase 1|MinIO setup · bucket policy · CORS config|0\.5 ngày|MinIO ready, CORS test pass|
|Phase 2|Luồng A: Upload qua API (FR-A01 → A03) + DB schema|1 ngày|Upload ảnh E2E pass|
|Phase 3|Luồng B: Presigned URL + Confirm + Move file (FR-B01 → B04)|1\.5 ngày|Upload audio E2E pass, không qua backend|
|Phase 4|Luồng C: Public URL + Stream + Metadata API (FR-C01 → C04)|0\.5 ngày|Stream audio seek được trên app|
|Phase 5|Cleanup scheduler + Integration test + Load test|0\.5 ngày|100 concurrent upload không lỗi|

✓ Tổng estimate: ~4 ngày. Có thể song song với việc setup MinIO infra.

