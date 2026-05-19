# Project Tree

> Auto-generated. Run `scripts/update-tree.ps1` to refresh, or it updates automatically when Claude adds a new service.
> Last updated: 2026-05-19

```
D:\MICROSERVICE_TEMPLATE
│
├── pom.xml                          ← Parent POM (quản lý toàn bộ)
├── docker-compose.yml               ← Start toàn bộ 1 lệnh
├── docker-compose.dev.yml           ← Dev mode (no resource limits)
├── .env / .env.example              ← Secrets config
│
├── common/                          ← Shared libs (dùng bởi tất cả services)
│   ├── common-dto/
│   │   └── ApiResponse, ErrorResponse, PageResponse
│   ├── common-security/
│   │   └── JwtTokenProvider, JwtAuthenticationFilter, SecurityConstants
│   └── common-events/
│       └── UserRegisteredEvent, PasswordResetEvent, FileUploadedEvent, NotificationEvent
│
├── infrastructure/                  ← Infra layer (không đổi giữa các dự án)
│   ├── eureka-server/               ← Service registry :8761
│   │   └── EurekaServerApplication.java
│   ├── config-server/               ← Config tập trung :8888
│   │   └── configs/
│   │       ├── api-gateway.yml
│   │       ├── auth-service.yml
│   │       ├── file-service.yml
│   │       ├── notification-service.yml
│   │       └── user-profile-service.yml
│   └── api-gateway/                 ← Entry point duy nhất :8080
│       └── filter/
│           ├── GlobalAuthFilter.java     ← JWT validate
│           └── RequestLoggingFilter.java
│
├── infra-services/                  ← Core services (không đổi giữa các dự án)
│   ├── auth-service/                ← JWT, login, register :8081
│   │   ├── entity/  User, Role, RefreshToken
│   │   ├── service/ AuthService.java
│   │   └── db/migration/ V1-V3 Flyway
│   ├── notification-service/        ← Email via RabbitMQ :8082
│   │   ├── listener/ NotificationEventListener.java
│   │   └── templates/ welcome.html, password-reset.html
│   └── file-service/                ← MinIO upload/serve :8083
│       └── service/ FileStorageService.java
│
├── business-services/               ← Domain cụ thể (thay theo từng dự án)
│   ├── user-profile-service/        ← Example :8091
│   │   ├── client/ FileServiceClient.java   ← Feign
│   │   └── listener/ UserRegisteredListener.java
│   └── vocal-detector/              ← Python FastAPI :8765
│       ├── app/
│       │   ├── main.py              ← FastAPI endpoints
│       │   ├── detector.py          ← Essentia AI pipeline
│       │   └── schemas.py           ← Pydantic models
│       ├── scripts/ download_models.sh / .ps1
│       └── data-test/ co_loi.mp3, test_khong_loi.mp3
│
└── docs/
    ├── ARCHITECTURE.md
    ├── PRD.md
    ├── API.md
    ├── RUNBOOK.md
    ├── CONTRIBUTING.md
    ├── PROJECT_TREE.md              ← file này
    └── CHECKPOINT.md
```

## Service Port Reference

| Service | Port | Language | Role |
|---------|------|----------|------|
| API Gateway | 8080 | Java | Entry point, JWT, rate limit |
| Auth Service | 8081 | Java | Register, login, JWT lifecycle |
| Notification Service | 8082 | Java | Email via RabbitMQ |
| File Service | 8083 | Java | Upload/serve via MinIO |
| User Profile Service | 8091 | Java | Example business service |
| vocal-detector | 8765 | Python | AI vocal detection (Essentia) |
| Eureka Server | 8761 | Java | Service registry |
| Config Server | 8888 | Java | Centralised config |
| PostgreSQL | 5432 | — | Main database (4 DBs) |
| Redis | 6379 | — | Rate limiting cache |
| RabbitMQ | 5672/15672 | — | Async messaging |
| MinIO | 9000/9001 | — | Object storage |
| Zipkin | 9411 | — | Distributed tracing |

## Thêm service mới

Khi thêm service mới, cập nhật file này:
1. Thêm dòng vào tree (phần `business-services/`)
2. Thêm row vào bảng Port Reference
3. Chạy `scripts/update-tree.ps1` để tự động refresh phần tree raw

---

*Tổng hiện tại: 11 Java modules + 1 Python service = 12 services*
