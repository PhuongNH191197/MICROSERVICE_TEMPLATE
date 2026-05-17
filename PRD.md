# PRD — Microservice Platform

## Vision

Build a reusable Spring Boot microservices platform that serves as the foundation for any future project.
Phase 1 (complete) provides infrastructure services that never need to change between projects.
Phase 2 and beyond contains project-specific business services that plug into the infra layer.

**Goals:**
- Zero-config startup: `docker-compose up -d` starts all services
- Secure by default: JWT validation at Gateway, BCrypt passwords, hashed refresh tokens
- Event-driven: services communicate via RabbitMQ; no direct DB sharing
- Observable: health endpoints, Zipkin tracing, structured logging
- Extensible: add a new business service in <1 hour using documented patterns

---

## Phase 1 — Infrastructure Services

### 1. Eureka Server (port 8761)

**Purpose:** Service registry. All Spring Boot services auto-register on startup. Gateway resolves `lb://service-name` via Eureka.

**Business rules:**
- Dashboard protected with basic auth (user: `eureka`, password: from env)
- Services with failed health checks are removed from registry automatically

**No REST API exposed to clients.**

---

### 2. Config Server (port 8888)

**Purpose:** Centralised configuration. Each service fetches its `application.yml` from Config Server on startup instead of bundling config locally.

**Business rules:**
- Configs stored at `infrastructure/config-server/src/main/resources/configs/{service-name}.yml`
- Native (classpath) profile — no git server required for dev
- Adding a new service: create `configs/{new-service}.yml`, add `spring.config.import` in service's `application.yml`

**No REST API exposed to clients.**

---

### 3. API Gateway (port 8080)

**Purpose:** Single entry point for all client requests. Handles JWT validation, rate limiting, logging, CORS, and routing.

**Routing table:**

| Path prefix | Target service |
|-------------|---------------|
| /api/auth/** | auth-service |
| /api/files/** | file-service |
| /api/notifications/** | notification-service |
| /api/users/** | user-profile-service |

**JWT whitelist (no token required):**
- POST /api/auth/register
- POST /api/auth/login
- POST /api/auth/refresh

**Business rules:**
- Rate limit: 100 requests/minute per IP (Redis-backed)
- On valid JWT: forward X-User-Id, X-User-Email, X-User-Roles to downstream
- On invalid JWT: return 401 immediately, never forward request
- CORS: configurable via env (all origins allowed in dev)

**Data model:** Stateless — no database. Redis for rate limit counters only.

---

### 4. Auth Service (port 8081)

**Purpose:** User registration, login, JWT issuance, and token lifecycle management.

**API endpoints:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/auth/register | None | Register new user, publish UserRegisteredEvent |
| POST | /api/auth/login | None | Login, return access + refresh tokens |
| POST | /api/auth/refresh | None | Rotate refresh token, return new pair |
| POST | /api/auth/logout | None | Revoke refresh token |
| GET | /api/auth/me | Bearer | Return current user info |
| POST | /api/auth/validate | None (internal) | Validate token, return userId+email+roles (called by Gateway) |
| POST | /api/auth/forgot-password | None | Publish PasswordResetEvent |
| POST | /api/auth/reset-password | None | Validate reset token, update password |

**Business rules:**
- Passwords hashed with BCrypt strength 12
- Access token: 15 min expiry, signed HS512
- Refresh token: 7 day expiry, stored as SHA-256 hash (never raw)
- Token rotation: every /refresh issues new token, revokes old
- Max 5 active refresh tokens per user — oldest auto-revoked
- Roles: ROLE_USER (default on register), ROLE_ADMIN (manual assignment)
- Disabled users cannot login

**Data model:**
```
users(id UUID, email UNIQUE, password_hash, full_name, enabled, created_at, updated_at)
roles(id, name UNIQUE)
user_roles(user_id, role_id)
refresh_tokens(id UUID, user_id, token_hash, expires_at, revoked, created_at)
```

**Events published:**
- `user.registered` → UserRegisteredEvent { userId, email, fullName }
- `user.password.reset` → PasswordResetEvent { userId, email, resetToken }

---

### 5. Notification Service (port 8082)

**Purpose:** Send email notifications triggered by RabbitMQ events. Never called directly by other services.

**API endpoints (internal only, not exposed via Gateway):**

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/notifications/logs | List notification logs (by userId, paginated) |

**Business rules:**
- Listens only to RabbitMQ — zero HTTP coupling to other services
- `user.registered` event → send welcome email (welcome.html template)
- `user.password.reset` event → send password reset email (password-reset.html)
- Failed sends retry 3× via dead letter queue (notification.dlq)
- All sends (success + failure) logged to notification_logs table
- Email via JavaMailSender + SMTP (configurable host/port/auth)

**Data model:**
```
notification_logs(id UUID, recipient, type, subject, status[PENDING/SENT/FAILED],
                  sent_at, error_message, created_at)
```

**Events consumed:**
- `user.registered` (from auth.exchange)
- `user.password.reset` (from auth.exchange)

---

### 6. File Service (port 8083)

**Purpose:** Upload, serve, and soft-delete files. Uses MinIO as S3-compatible object storage.

**API endpoints:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/files/upload | Bearer (via Gateway) | Upload file, return metadata + URL |
| GET | /api/files/{fileId} | Bearer | Redirect to presigned URL (1h expiry) |
| DELETE | /api/files/{fileId} | Bearer | Soft delete (mark deleted in DB) |
| GET | /api/files/user/{userId} | Bearer | List files for a user |

**Business rules:**
- Max file size: 10 MB
- Allowed types: jpg, jpeg, png, pdf, docx
- Files stored in MinIO buckets: avatars, documents, public, private
- userId extracted from X-User-Id header (injected by Gateway)
- Presigned URLs valid for 1 hour
- Delete is soft (sets deleted=true); files remain in MinIO

**Data model:**
```
files(id UUID, user_id, original_name, stored_name, bucket, size,
      content_type, public_file, deleted, created_at)
```

**Events published:**
- `file.uploaded` → FileUploadedEvent { fileId, userId, fileName, url }

---

## Phase 2 — Business Services

### 7. User Profile Service (port 8091)

**Purpose:** Stores extended user profile data. Demonstrates how business services integrate with the infra layer.

**API endpoints:**

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | /api/users/profile | Bearer | Get my profile |
| PUT | /api/users/profile | Bearer | Update my profile |
| POST | /api/users/avatar | Bearer | Upload avatar (calls file-service via Feign) |

**Business rules:**
- Profile created automatically when `user.registered` event received
- userId comes from X-User-Id header — never calls auth-service directly
- Avatar upload delegates to file-service via Feign Client
- Profile id = auth userId (same UUID, no FK constraint — services are decoupled)

**Data model:**
```
profiles(id VARCHAR PK, full_name, avatar_url, bio, phone, address, created_at, updated_at)
```

**Events consumed:**
- `user.registered` (auto-create empty profile)

**Feign clients:**
- FileServiceClient → POST /api/files/upload

---

## Cross-Cutting Concerns

### Security
- All responses use `ApiResponse<T>` wrapper — error messages never expose internals
- `GlobalExceptionHandler` in every service catches all exceptions, returns consistent ErrorResponse
- Validation annotations (`@NotBlank`, `@Email`, `@Size`) on all request DTOs
- No hardcoded secrets — all from Config Server or environment variables

### Logging
- `@Slf4j` on all service classes
- INFO level: business events (register, login, file upload, notification sent)
- DEBUG level: internals (token parsing, DB queries)
- Gateway logs: method, path, status, duration for every request

### Error Handling

| HTTP Status | When |
|------------|------|
| 200 | Success |
| 400 | Bad request / duplicate email / validation failure |
| 401 | Invalid or missing JWT |
| 403 | Valid JWT but insufficient roles |
| 404 | Resource not found |
| 500 | Unexpected server error |

### Observability
- `/actuator/health` on every service
- Zipkin distributed tracing (port 9411)
- RabbitMQ Management UI (port 15672)

---

## Build Order and Dependencies

```
1.  common/common-dto          (no dependencies)
2.  common/common-security     (depends on common-dto)
3.  common/common-events       (no dependencies)
4.  infrastructure/eureka-server   (no common deps)
5.  infrastructure/config-server   (no common deps)
6.  infrastructure/api-gateway     (no common deps)
7.  infra-services/auth-service    (common-dto, common-security, common-events)
8.  infra-services/notification-service (common-dto, common-events)
9.  infra-services/file-service    (common-dto, common-events)
10. business-services/user-profile-service (common-dto, common-events)
```

Maven build: `mvn clean package -DskipTests` (builds all in correct order via parent POM module declaration).

---

## How to Add a New Business Service

1. Create `business-services/{your-service}/` with same structure as user-profile-service
2. Add module to root `pom.xml` `<modules>` section
3. Create `infrastructure/config-server/src/main/resources/configs/{your-service}.yml`
4. Add route to `infrastructure/api-gateway/src/main/resources/application.yml`
5. Add service to `docker-compose.yml` with healthcheck and `depends_on`
6. Rebuild: `mvn clean package -DskipTests && docker-compose up -d --build {your-service}`

The service auto-registers to Eureka. Gateway discovers it. Config Server serves its config. Zero changes to infra services.

---

## Done Checklist

### Phase 1 — Infrastructure
- [x] Parent POM with Spring Boot 3.2.4 + Spring Cloud 2023.0.1 BOM
- [x] common-dto: ApiResponse, PageResponse, ErrorResponse
- [x] common-security: JwtTokenProvider, JwtAuthenticationFilter, SecurityConstants
- [x] common-events: UserRegisteredEvent, PasswordResetEvent, NotificationEvent, FileUploadedEvent
- [x] eureka-server: basic auth, dashboard
- [x] config-server: native profile, configs for all 5 services
- [x] api-gateway: GlobalAuthFilter (WebClient), RequestLoggingFilter, Redis rate limiting, 4 routes
- [x] auth-service: register, login, refresh, logout, validate, forgot/reset password, Flyway migrations
- [x] notification-service: email queue, DLQ, Thymeleaf templates, notification_logs
- [x] file-service: MinIO upload, presigned URLs, soft delete, Flyway migrations
- [x] user-profile-service: Feign to file-service, profile auto-create on event
- [x] docker-compose.yml: 12 services, healthchecks, startup order
- [x] .env.example, README.md, ARCHITECTURE.md, CHECKPOINT.md

### Tests Written
- [x] common-dto: ApiResponseTest (7 unit tests)
- [x] common-security: JwtTokenProviderTest (3 unit tests)
- [x] auth-service: AuthServiceTest (6 Mockito unit tests)
- [x] auth-service: AuthServiceIntegrationTest (4 Testcontainers tests, PostgreSQL container)

### Build
- [x] Session 1: All 10 modules BUILD SUCCESS
- [x] Fixed: flyway-database-postgresql version (9.22.3)
- [x] Fixed: @EnableEurekaClient deprecated → @EnableDiscoveryClient
- [x] Fixed: DTO constructor calls → setter pattern in tests
- [x] Fixed: User.id UUID type vs String mismatch in tests

---

## Remaining TODO

### Immediate
- [ ] Run `docker-compose up -d` and verify all services healthy
- [ ] Verify 7 services appear in Eureka dashboard
- [ ] Run smoke tests (register → login → upload → profile)
- [ ] Fix any runtime errors discovered during smoke tests

### Tests Missing
- [ ] notification-service: test NotificationEventListener (mock EmailService)
- [ ] file-service: test FileStorageService (mock MinioClient)
- [ ] user-profile-service: test UserProfileService (mock Feign client)
- [ ] api-gateway: test GlobalAuthFilter (WebTestClient)

### Future Business Services
- [ ] Order Service (e-commerce example)
- [ ] Product Service (catalog example)
- [ ] Payment Service (Stripe integration example)
- [ ] Chat Service (WebSocket example)
- [ ] Analytics Service (event sourcing example)

### Production Hardening
- [ ] Switch Config Server to git backend (for production)
- [ ] Add Spring Cloud Circuit Breaker (Resilience4j) to Feign clients
- [ ] Add distributed tracing propagation headers
- [ ] Kubernetes manifests (Helm charts)
- [ ] CI/CD pipeline (GitHub Actions)
- [ ] Secrets management (HashiCorp Vault or AWS Secrets Manager)
