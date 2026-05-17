# Hướng dẫn cài đặt — Microservice Platform

> Viết bằng tiếng Việt. Dành cho developer mới tiếp cận project này lần đầu.

---

## Mục lục

1. [Yêu cầu hệ thống](#1-yêu-cầu-hệ-thống)
2. [Cài đặt lần đầu](#2-cài-đặt-lần-đầu)
3. [Khởi động hệ thống](#3-khởi-động-hệ-thống)
4. [Kiểm tra hệ thống hoạt động](#4-kiểm-tra-hệ-thống-hoạt-động)
5. [Smoke test sau khi cài](#5-smoke-test-sau-khi-cài)
6. [Cấu hình môi trường](#6-cấu-hình-môi-trường)
7. [Cấu hình email (Notification Service)](#7-cấu-hình-email-notification-service)
8. [Cấu hình MinIO (File Service)](#8-cấu-hình-minio-file-service)
9. [Development workflow](#9-development-workflow)
10. [Thêm Business Service mới](#10-thêm-business-service-mới)
11. [Troubleshooting](#11-troubleshooting)
12. [Tắt hệ thống](#12-tắt-hệ-thống)

---

## 1. Yêu cầu hệ thống

| Công cụ | Phiên bản tối thiểu | Ghi chú |
|---------|---------------------|---------|
| Java | 17+ | Kiểm tra: `java -version` |
| Maven | 3.9+ | Kiểm tra: `mvn -version` |
| Docker Desktop | Mới nhất | Phải đang chạy trước khi thực hiện bước 3 |
| Git | Bất kỳ | Kiểm tra: `git --version` |
| RAM | Tối thiểu 8GB | Khuyến nghị 16GB (12 container chạy đồng thời) |
| Disk | 10GB trống | Cho Docker images + PostgreSQL data |

**Kiểm tra nhanh:**
```bash
java -version      # phải ra java 17+
mvn -version       # phải ra 3.9+
docker ps          # phải không báo lỗi (Docker Desktop đang chạy)
```

---

## 2. Cài đặt lần đầu

### Bước 1: Clone repository

```bash
git clone <repo-url> MICROSERVICE_TEMPLATE
cd MICROSERVICE_TEMPLATE
```

### Bước 2: Copy file cấu hình môi trường

**Windows (PowerShell):**
```powershell
copy .env.example .env
```

**Linux/Mac:**
```bash
cp .env.example .env
```

### Bước 3: Điền thông tin vào file `.env`

Mở file `.env` bằng bất kỳ text editor nào. Dưới đây là giải thích từng biến:

#### Database (PostgreSQL)
```env
POSTGRES_DB=platform_db        # Tên database mặc định — giữ nguyên
POSTGRES_USER=platform_user    # Username PostgreSQL — có thể đổi
POSTGRES_PASSWORD=platform_pass # ⚠️ PHẢI đổi nếu deploy production
```

#### Redis (dùng cho rate limiting trong API Gateway)
```env
REDIS_PASSWORD=redis_pass      # ⚠️ Đổi cho production
```

#### RabbitMQ (message broker — auth events, email events)
```env
RABBITMQ_DEFAULT_USER=rabbit_user   # Username RabbitMQ Management UI
RABBITMQ_DEFAULT_PASS=rabbit_pass   # ⚠️ Đổi cho production
```

#### MinIO (lưu trữ file — ảnh, tài liệu)
```env
MINIO_ROOT_USER=minio_admin        # Username MinIO Console
MINIO_ROOT_PASSWORD=minio_pass123  # Tối thiểu 8 ký tự. ⚠️ Đổi cho production
```

#### Eureka Server (service registry)
```env
EUREKA_USERNAME=admin       # Đăng nhập Eureka dashboard tại http://localhost:8761
EUREKA_PASSWORD=admin_pass  # ⚠️ Đổi cho production
```

#### Config Server (cung cấp config cho tất cả service)
```env
CONFIG_SERVER_USERNAME=config_admin  # Dùng nội bộ, các service dùng để fetch config
CONFIG_SERVER_PASSWORD=config_pass   # ⚠️ Đổi cho production
```

#### JWT Secret (quan trọng nhất — bảo mật token)
```env
# ⚠️ BẮT BUỘC ĐỔI — dùng chuỗi ngẫu nhiên >= 64 ký tự
JWT_SECRET=your-256-bit-secret-key-change-in-production-must-be-at-least-32-chars

# Sinh JWT_SECRET ngẫu nhiên (chạy lệnh này):
# openssl rand -base64 64

JWT_ACCESS_EXPIRY_MS=900000      # Access token: 15 phút (900,000ms) — giữ nguyên
JWT_REFRESH_EXPIRY_MS=604800000  # Refresh token: 7 ngày — giữ nguyên

JWT_REFRESH_SECRET=your-refresh-secret-key-here-change-in-production  # ⚠️ Đổi
JWT_EXPIRATION=900000            # Dùng bởi một số service — giữ bằng JWT_ACCESS_EXPIRY_MS
JWT_REFRESH_EXPIRATION=604800000 # Giữ nguyên
```

#### Email (Notification Service)
```env
MAIL_HOST=smtp.gmail.com    # Gmail SMTP — xem Mục 7 để cấu hình
MAIL_PORT=587               # Port TLS — giữ nguyên với Gmail
MAIL_USERNAME=your-email@gmail.com  # Email Gmail của bạn
MAIL_PASSWORD=your-app-password     # App Password (KHÔNG phải mật khẩu Gmail) — xem Mục 7
MAIL_FROM=noreply@platform.com      # Tên hiển thị email gửi đi
```

#### URL ứng dụng
```env
APP_URL=http://localhost:8080  # URL public — đổi khi deploy production
```

#### URLs nội bộ (giữ nguyên — Docker network)
```env
EUREKA_SERVER_URL=http://eureka-server:8761/eureka/  # Các service dùng để register
CONFIG_SERVER_URL=http://config-server:8888           # Các service fetch config từ đây
```

### Bước 4: Tạo thư mục cần thiết

Project không cần tạo thư mục thêm — Docker tự tạo volumes (`postgres_data`, `minio_data`).

---

## 3. Khởi động hệ thống

### Bước 1: Build toàn bộ JAR

```bash
cd D:\MICROSERVICE_TEMPLATE
mvn clean install -DskipTests
```

Lệnh này build tất cả 11 module theo đúng thứ tự (xem PRD.md). Lần đầu mất ~3-5 phút để download dependencies.

**Kết quả mong đợi:**
```
[INFO] BUILD SUCCESS
[INFO] Total time: X:XX min
```

Nếu thất bại → xem [Mục 11 Troubleshooting](#11-troubleshooting).

### Bước 2: Start infrastructure (tất cả service)

```bash
docker-compose up -d
```

Docker sẽ pull images (lần đầu ~5-10 phút) rồi start 12 container theo thứ tự:

```
postgresql → redis → rabbitmq → minio → zipkin
  → eureka-server → config-server
  → api-gateway → auth-service → notification-service → file-service → user-profile-service
```

### Bước 3: Kiểm tra trạng thái

```bash
docker-compose ps
```

Tất cả service phải có status `healthy` hoặc `running`. Chờ ~90 giây sau khi chạy lệnh up.

```bash
# Xem log realtime của tất cả service
docker-compose logs -f

# Xem log của 1 service cụ thể
docker-compose logs -f auth-service
docker-compose logs -f api-gateway
```

### Thứ tự khởi động (startup order)

Hệ thống có `depends_on` + healthcheck nên tự động đúng thứ tự:

| Lượt | Service | Chờ điều kiện |
|------|---------|---------------|
| 1 | postgresql, redis, rabbitmq, minio, zipkin | — |
| 2 | eureka-server | postgresql healthy |
| 3 | config-server | eureka-server healthy |
| 4 | api-gateway | config-server healthy + redis healthy |
| 5 | auth-service | postgresql + rabbitmq + config-server healthy |
| 5 | notification-service | postgresql + rabbitmq + config-server healthy |
| 5 | file-service | postgresql + rabbitmq + minio + config-server healthy |
| 5 | user-profile-service | postgresql + rabbitmq + config-server healthy |

---

## 4. Kiểm tra hệ thống hoạt động

Sau khi `docker-compose up -d` hoàn tất, kiểm tra từng dashboard:

### Eureka Dashboard — Service Registry

```
URL:   http://localhost:8761
Login: admin / admin_pass  (giá trị EUREKA_USERNAME/EUREKA_PASSWORD trong .env)
```

**Mong đợi:** 7 service phải xuất hiện trong danh sách:
- `API-GATEWAY`
- `AUTH-SERVICE`
- `NOTIFICATION-SERVICE`
- `FILE-SERVICE`
- `USER-PROFILE-SERVICE`
- `CONFIG-SERVER`
- (eureka-server không tự register)

Nếu service chưa hiện → chờ thêm 30 giây → F5 lại. Service cần 30-60 giây để register sau khi start.

### RabbitMQ Management UI

```
URL:   http://localhost:15672
Login: rabbit_user / rabbit_pass  (giá trị RABBITMQ_DEFAULT_USER/PASS trong .env)
```

**Mong đợi:**
- Tab **Queues**: thấy `notification.email.queue`, `notification.dlq`, `user.profile.queue`
- Tab **Exchanges**: thấy `auth.exchange`

### MinIO Console

```
URL:   http://localhost:9001
Login: minio_admin / minio_pass123  (giá trị MINIO_ROOT_USER/PASSWORD trong .env)
```

**Mong đợi:** Đăng nhập thành công, thấy trang Object Browser.

### Zipkin — Distributed Tracing

```
URL: http://localhost:9411
```

Không cần đăng nhập. Lúc mới start sẽ không có trace — sẽ có data sau khi gọi API.

### Health check từng service

```bash
# API Gateway
curl http://localhost:8080/actuator/health

# Auth Service
curl http://localhost:8081/actuator/health

# Notification Service
curl http://localhost:8082/actuator/health

# File Service
curl http://localhost:8083/actuator/health

# User Profile Service
curl http://localhost:8091/actuator/health
```

**Kết quả mong đợi:**
```json
{"status":"UP"}
```

---

## 5. Smoke test sau khi cài

Chạy lần lượt các lệnh sau. Mỗi bước dùng kết quả từ bước trước.

### Test 1: Đăng ký user

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "Password123!",
    "fullName": "Test User"
  }'
```

**Kết quả mong đợi:**
```json
{
  "success": true,
  "data": {
    "id": "uuid-here",
    "email": "test@example.com",
    "fullName": "Test User"
  },
  "message": null
}
```

Nếu cấu hình email, bạn sẽ nhận email chào mừng.

### Test 2: Đăng nhập — lấy token

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "Password123!"
  }'
```

**Kết quả mong đợi:**
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzUxMiJ9...",
    "refreshToken": "abc123...",
    "tokenType": "Bearer"
  }
}
```

**Lưu lại `accessToken`** — dùng cho các bước sau. Thay `YOUR_TOKEN` bằng giá trị này.

### Test 3: Xem profile

```bash
curl http://localhost:8080/api/users/profile \
  -H "Authorization: Bearer YOUR_TOKEN"
```

**Kết quả mong đợi:**
```json
{
  "success": true,
  "data": {
    "id": "uuid-here",
    "fullName": "Test User",
    "avatarUrl": null,
    "bio": null,
    "phone": null,
    "address": null
  }
}
```

Profile được tạo tự động khi đăng ký (qua RabbitMQ event `user.registered`).

### Test 4: Upload file

Tạo 1 file test nhỏ trước:
```bash
# Tạo file test (hoặc dùng bất kỳ file .jpg nào)
echo "test" > test.txt
```

```bash
curl -X POST http://localhost:8080/api/files/upload \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "file=@test.txt"
```

**Kết quả mong đợi:**
```json
{
  "success": true,
  "data": {
    "fileId": "uuid-here",
    "originalName": "test.txt",
    "url": "http://localhost:9000/...",
    "size": 5,
    "contentType": "text/plain"
  }
}
```

**Nếu tất cả 4 test PASS** → hệ thống hoạt động bình thường.

---

## 6. Cấu hình môi trường

### Các file config trong Config Server

```
infrastructure/config-server/src/main/resources/configs/
├── api-gateway.yml          # Route table, rate limit, CORS, JWT whitelist
├── auth-service.yml         # JWT config, BCrypt strength, token expiry
├── notification-service.yml # Email template, SMTP settings, queue names
├── file-service.yml         # MinIO buckets, file size limit, allowed types
└── user-profile-service.yml # Feign client config, profile settings
```

Mỗi service khi start sẽ **fetch config từ Config Server** thay vì đọc local file. Sửa file `.yml` trong thư mục trên → restart service tương ứng.

### Cách thay đổi port

Ví dụ đổi API Gateway từ 8080 → 9090:

1. Sửa `docker-compose.yml`:
```yaml
api-gateway:
  ports: ["9090:8080"]  # host:container
```

2. Restart Gateway:
```bash
docker-compose up -d --no-deps api-gateway
```

3. Cập nhật `APP_URL` trong `.env`:
```env
APP_URL=http://localhost:9090
```

### Cách thay đổi database

Sửa trong `.env`:
```env
POSTGRES_DB=my_new_db
POSTGRES_USER=my_user
POSTGRES_PASSWORD=my_strong_password
```

**Cảnh báo:** Nếu đã có data, xóa volume trước khi đổi:
```bash
docker-compose down -v  # XÓA TOÀN BỘ DATA
docker-compose up -d
```

### Cách thay đổi JWT secret cho production

Tạo secret ngẫu nhiên mạnh:
```bash
# Linux/Mac
openssl rand -base64 64

# Windows PowerShell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
```

Điền vào `.env`:
```env
JWT_SECRET=<chuỗi vừa sinh>
JWT_REFRESH_SECRET=<chuỗi khác vừa sinh>
```

Restart auth-service và api-gateway (mọi token cũ sẽ bị invalid):
```bash
docker-compose up -d --no-deps auth-service api-gateway
```

### Cách bật/tắt từng service

Tắt 1 service (giữ data):
```bash
docker-compose stop notification-service
```

Bật lại:
```bash
docker-compose start notification-service
```

Tắt hoàn toàn và xóa container (data vẫn giữ trong volume):
```bash
docker-compose rm -f notification-service
```

---

## 7. Cấu hình email (Notification Service)

Notification Service gửi email khi user đăng ký và khi reset mật khẩu. Cần cấu hình Gmail App Password.

### Bước 1: Bật 2-Factor Authentication trên Gmail

Vào Google Account → Security → bật **2-Step Verification**.

### Bước 2: Tạo App Password

1. Vào Google Account → Security → **App passwords**
2. Chọn **Other (Custom name)** → nhập "Microservice Platform"
3. Nhấn **Generate**
4. Copy **16 ký tự** hiện ra (dạng: `xxxx xxxx xxxx xxxx`)

### Bước 3: Điền vào `.env`

```env
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=your-actual-email@gmail.com  # Email Gmail của bạn
MAIL_PASSWORD=xxxxxxxxxxxxxxxx             # App Password 16 ký tự (không có space)
MAIL_FROM=noreply@yourplatform.com        # Tên hiển thị — có thể đặt tùy ý
```

### Bước 4: Restart notification-service

```bash
docker-compose up -d --no-deps notification-service
```

### Test gửi email

```bash
# Đăng ký user mới — nếu email đúng sẽ nhận welcome email
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "your-real-email@gmail.com",
    "password": "Password123!",
    "fullName": "Test Email"
  }'
```

Kiểm tra inbox. Không thấy → kiểm tra spam và xem log:
```bash
docker-compose logs -f notification-service
```

---

## 8. Cấu hình MinIO (File Service)

### Truy cập MinIO Console

```
URL:   http://localhost:9001
Login: minio_admin / minio_pass123  (từ .env: MINIO_ROOT_USER / MINIO_ROOT_PASSWORD)
```

### Tạo bucket thủ công (nếu cần)

File Service tự tạo bucket khi start. Nếu muốn tạo thủ công:

1. Vào **Object Browser** → nhấn **Create Bucket**
2. Tạo 4 bucket:
   - `avatars` — ảnh đại diện user
   - `documents` — file tài liệu
   - `public` — file public (ai cũng xem được)
   - `private` — file private (cần auth)

### Cấu hình bucket Public

Để file trong bucket `public` truy cập được không cần auth:

1. Chọn bucket `public` → **Manage** → **Access Policy**
2. Chọn **Public** → Save

### Cấu hình bucket Private

Bucket `private` và `avatars` giữ **Private** (mặc định). File-service tạo presigned URL (hiệu lực 1 giờ) khi user request xem file.

---

## 9. Development workflow

### Chạy 1 service riêng lẻ để debug

Dừng container của service đó, rồi chạy trực tiếp bằng Maven:

```bash
# Dừng container auth-service
docker-compose stop auth-service

# Chạy auth-service local (từ thư mục root project)
cd infra-services/auth-service
mvn spring-boot:run
```

Service local vẫn kết nối PostgreSQL, Redis, RabbitMQ đang chạy trong Docker (qua `localhost:5432`, `localhost:6379`, v.v.).

### Hot-reload khi sửa code

```bash
# Terminal 1: chạy service với devtools
mvn spring-boot:run

# Terminal 2: trigger reload sau khi sửa code
mvn compile
```

Hoặc dùng IntelliJ IDEA với **Build Project** (Ctrl+F9) — Spring DevTools tự detect thay đổi.

### Xem log realtime

```bash
# Tất cả service
docker-compose logs -f

# Chỉ 1 service
docker-compose logs -f auth-service

# Nhiều service cùng lúc
docker-compose logs -f auth-service api-gateway

# Xem 100 dòng cuối rồi follow
docker-compose logs --tail=100 -f auth-service
```

### Connect IntelliJ IDEA Remote Debug

Thêm JVM debug flag vào `docker-compose.yml` cho service muốn debug:

```yaml
auth-service:
  environment:
    JAVA_TOOL_OPTIONS: "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
  ports:
    - "8081:8081"
    - "5005:5005"  # debug port
```

Restart service:
```bash
docker-compose up -d --no-deps auth-service
```

Trong IntelliJ IDEA:
1. **Run** → **Edit Configurations** → **+** → **Remote JVM Debug**
2. Host: `localhost`, Port: `5005`
3. Nhấn **Debug** → đặt breakpoint → gọi API → IDEA dừng tại breakpoint

---

## 10. Thêm Business Service mới

### Bước 1: Copy từ user-profile-service

```powershell
# Windows PowerShell
xcopy /E /I business-services\user-profile-service business-services\my-new-service
```

```bash
# Linux/Mac
cp -r business-services/user-profile-service business-services/my-new-service
```

### Bước 2: Đổi tên artifact trong pom.xml

Sửa `business-services/my-new-service/pom.xml`:
```xml
<artifactId>my-new-service</artifactId>
<name>my-new-service</name>
```

Đổi tên package trong Java (dùng IntelliJ: Refactor → Rename Package):
```
com.platform.userprofile → com.platform.mynewservice
```

### Bước 3: Đăng ký vào root `pom.xml`

```xml
<modules>
    <!-- ... các module hiện tại ... -->
    <module>business-services/my-new-service</module>
</modules>
```

### Bước 4: Tạo config file

Tạo: `infrastructure/config-server/src/main/resources/configs/my-new-service.yml`

```yaml
server:
  port: 8092

spring:
  application:
    name: my-new-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${POSTGRES_DB:platform_db}
    username: ${POSTGRES_USER:platform_user}
    password: ${POSTGRES_PASSWORD:platform_pass}
```

### Bước 5: Thêm route vào API Gateway

Sửa `infrastructure/config-server/src/main/resources/configs/api-gateway.yml`:

```yaml
spring:
  cloud:
    gateway:
      routes:
        # ... các route hiện tại ...
        - id: my-new-service
          uri: lb://my-new-service   # phải khớp với spring.application.name
          predicates:
            - Path=/api/mynew/**
          filters:
            - StripPrefix=0
```

### Bước 6: Thêm vào docker-compose.yml

```yaml
my-new-service:
  build: ./business-services/my-new-service
  ports: ["8092:8092"]
  environment:
    EUREKA_USERNAME: ${EUREKA_USERNAME}
    EUREKA_PASSWORD: ${EUREKA_PASSWORD}
    CONFIG_SERVER_USERNAME: ${CONFIG_SERVER_USERNAME}
    CONFIG_SERVER_PASSWORD: ${CONFIG_SERVER_PASSWORD}
    EUREKA_HOST: eureka-server
    CONFIG_SERVER_HOST: config-server
    DB_HOST: postgresql
    POSTGRES_DB: ${POSTGRES_DB}
    POSTGRES_USER: ${POSTGRES_USER}
    POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
  depends_on:
    postgresql:
      condition: service_healthy
    config-server:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8092/actuator/health"]
    interval: 15s
    timeout: 10s
    retries: 10
```

### Bước 7: Build và start

```bash
# Build chỉ service mới
mvn clean package -DskipTests -pl business-services/my-new-service -am

# Start (các service khác vẫn đang chạy)
docker-compose up -d --build my-new-service
```

### Checklist trước khi chạy

- [ ] `artifactId` trong pom.xml đã đổi
- [ ] Package name đã đổi (`com.platform.mynewservice`)
- [ ] `spring.application.name` trong config YML khớp tên Eureka
- [ ] Port không trùng (8081-8083, 8091 đã dùng)
- [ ] Route trong api-gateway.yml đã thêm
- [ ] Module đã khai báo trong root pom.xml
- [ ] Config file đã tạo trong `config-server/configs/`

Service tự động register vào Eureka. Gateway tự động discover qua `lb://my-new-service`. Không cần sửa infra services.

---

## 11. Troubleshooting

### Service không start được

```bash
# Xem log lỗi
docker-compose logs --tail=50 auth-service
```

**Nguyên nhân thường gặp:**
- Config Server chưa healthy → chờ thêm 30 giây
- Thiếu biến môi trường trong `.env`
- Port bị chiếm → xem mục "Port bị chiếm" bên dưới

### Không kết nối được database

**Triệu chứng:** Log báo `Connection refused` hoặc `FATAL: password authentication failed`.

```bash
# Kiểm tra PostgreSQL đang chạy
docker-compose ps postgresql

# Test kết nối trực tiếp
docker exec -it microservice_template-postgresql-1 \
  psql -U platform_user -d platform_db
```

**Kiểm tra:** `POSTGRES_USER` và `POSTGRES_PASSWORD` trong `.env` phải khớp nhau. Nếu đổi password sau khi volume đã tồn tại → phải `docker-compose down -v` rồi up lại.

### Token JWT invalid

**Triệu chứng:** API trả về 401 dù đã đăng nhập.

**Nguyên nhân:**
1. `JWT_SECRET` thay đổi → đăng nhập lại lấy token mới
2. Access token hết hạn (15 phút) → gọi `/api/auth/refresh`
3. `JWT_SECRET` khác nhau giữa auth-service và api-gateway → restart cả 2:

```bash
docker-compose up -d --no-deps auth-service api-gateway
```

### RabbitMQ không nhận event

**Triệu chứng:** Đăng ký xong nhưng không nhận email, profile không tạo.

```bash
# Kiểm tra queue tồn tại
# → http://localhost:15672 → Queues tab

# Xem log consumer
docker-compose logs notification-service
docker-compose logs user-profile-service
```

**Kiểm tra:** `RABBITMQ_DEFAULT_USER/PASS` trong `.env` đúng chưa. Queue `notification.email.queue` và `user.profile.queue` phải tồn tại.

### File upload thất bại

```bash
# Kiểm tra MinIO
curl http://localhost:9000/minio/health/live

# Log file-service
docker-compose logs file-service
```

**Kiểm tra:**
- File size không vượt quá 10MB
- File type phải là: jpg, jpeg, png, pdf, docx
- MinIO đang chạy: `docker-compose ps minio`

### Port bị chiếm

**Triệu chứng:** `Bind for 0.0.0.0:8080 failed: port is already allocated`.

```powershell
# Windows — tìm process đang dùng port 8080
netstat -ano | findstr :8080

# Kill process (thay PID)
taskkill /PID <PID> /F
```

```bash
# Linux/Mac
lsof -i :8080
kill -9 <PID>
```

### Out of memory (Docker)

**Triệu chứng:** Container bị kill, log báo `OOM`.

**Giải pháp:**
1. Docker Desktop → Settings → Resources → tăng Memory lên 8-12GB
2. Tắt bớt service không dùng: `docker-compose stop notification-service`

---

## 12. Tắt hệ thống

### Tắt toàn bộ — xóa container, giữ data

```bash
docker-compose down
```

Data trong PostgreSQL và MinIO vẫn được giữ trong Docker volumes.

### Tắt nhưng giữ container state

```bash
docker-compose stop
```

Start lại nhanh hơn (không rebuild):
```bash
docker-compose start
```

### Xóa toàn bộ data (reset sạch)

**Cảnh báo: Lệnh này xóa TOÀN BỘ dữ liệu, không thể khôi phục.**

```bash
docker-compose down -v
```

Flag `-v` xóa volumes `postgres_data` (toàn bộ database) và `minio_data` (toàn bộ file). Lần start tiếp theo sạch như cài mới.

### Xóa toàn bộ Docker images (giải phóng disk)

```bash
# Dừng và xóa container + volumes trước
docker-compose down -v

# Xóa images đã build
docker-compose down --rmi all

# Dọn sạch toàn bộ Docker (cẩn thận nếu có project Docker khác)
docker system prune -a
```

---

## Tóm tắt URLs và credentials

| Service | URL | Username | Password (từ `.env.example`) |
|---------|-----|----------|------------------------------|
| API Gateway | http://localhost:8080 | — | — |
| Eureka | http://localhost:8761 | admin | admin_pass |
| RabbitMQ UI | http://localhost:15672 | rabbit_user | rabbit_pass |
| MinIO Console | http://localhost:9001 | minio_admin | minio_pass123 |
| Zipkin | http://localhost:9411 | — | — |
| PostgreSQL | localhost:5432 | platform_user | platform_pass |

> Tất cả credentials trên là **mặc định từ `.env.example`**.
> Sau khi copy sang `.env`, hãy thay đổi tất cả trước khi deploy production.
