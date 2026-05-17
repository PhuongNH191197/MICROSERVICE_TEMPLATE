# Architecture — Microservice Platform

## System Overview

Reusable Spring Boot microservices ecosystem. Phase 1 provides infrastructure services deployable across any project. Phase 2 contains project-specific business services that plug into the infra layer.

**Goals:**
- Single `docker-compose up -d` to start everything
- All services auto-register to Eureka; Gateway handles routing + JWT validation
- Async communication via RabbitMQ; sync via Feign Client (load-balanced through Eureka)
- Every service independently deployable via Docker

---

## Architecture Diagram

```
                         CLIENTS (browser / mobile / curl)
                                       |
                              [ API Gateway :8080 ]
                              GlobalAuthFilter (JWT)
                              RequestLoggingFilter
                              RedisRateLimiter (100 req/min/IP)
                                       |
              ┌────────────────────────┼──────────────────────┐
              |                        |                       |
      /api/auth/**           /api/files/**           /api/users/**
              |                        |                       |
    [Auth Service :8081]   [File Service :8083]  [User Profile :8091]
         |    |                   |                     |
         |    |              [MinIO :9000]        [Feign → File]
         |    └── JWT validate ←──────── Gateway calls /api/auth/validate
         |
    [Notification :8082] ←── RabbitMQ events only (no direct HTTP)
         |
      [SMTP / email]

                    SHARED INFRASTRUCTURE
    ┌──────────────────────────────────────────────────┐
    │  PostgreSQL :5432   Redis :6379   RabbitMQ :5672 │
    │  MinIO :9000        Zipkin :9411                 │
    │  Eureka :8761       Config Server :8888          │
    └──────────────────────────────────────────────────┘

    All services register to Eureka. Config Server serves per-service YML.
    Gateway resolves lb://service-name via Eureka.
```

---

## Services

| Service | Port | Role | Tech |
|---------|------|------|------|
| eureka-server | 8761 | Service registry + dashboard | Spring Cloud Netflix Eureka, basic auth |
| config-server | 8888 | Centralised config for all services | Spring Cloud Config, native/classpath |
| api-gateway | 8080 | Single entry point, JWT guard, rate limit | Spring Cloud Gateway (WebFlux), Redis |
| auth-service | 8081 | User auth, JWT lifecycle, roles | Spring Security 6, jjwt 0.12.5, BCrypt 12, Flyway, PostgreSQL |
| notification-service | 8082 | Email notifications via events | RabbitMQ listener, Thymeleaf, JavaMailSender, Flyway, PostgreSQL |
| file-service | 8083 | File upload/serve/delete | MinIO, Flyway, PostgreSQL |
| user-profile-service | 8091 | User profile data (example business svc) | Feign Client, RabbitMQ listener, Flyway, PostgreSQL |

---

## Inter-Service Communication

### Synchronous (Feign Client + Eureka LB)

```
user-profile-service
  └── FileServiceClient (@FeignClient name="file-service")
        └── POST /api/files/upload  →  file-service
```

Only business services call other services via Feign. Infrastructure services never call each other.

### Asynchronous (RabbitMQ)

```
Exchange: auth.exchange (topic)

auth-service PUBLISHES:
  user.registered    →  UserRegisteredEvent  →  notification-service (welcome email)
                                             →  user-profile-service (create empty profile)
  user.password.reset → PasswordResetEvent  →  notification-service (reset email)

file-service PUBLISHES:
  file.uploaded      →  FileUploadedEvent   →  (future consumers)

Queues:
  notification.email.queue  — binds auth.exchange (user.registered, user.password.reset)
  notification.dlq          — dead letter, retry 3x
  user.profile.queue        — binds auth.exchange (user.registered)
```

### JWT Header Forwarding

Gateway validates token by calling `POST /api/auth/validate`, then injects:
- `X-User-Id` — user UUID
- `X-User-Email` — user email
- `X-User-Roles` — comma-separated roles

Downstream services read userId from `X-User-Id` header — they never call auth-service again.

---

## Database Schema

### auth-service (auth_db)

```sql
users          (id UUID PK, email UNIQUE, password_hash, full_name, enabled, created_at, updated_at)
roles          (id BIGSERIAL PK, name UNIQUE)
user_roles     (user_id FK, role_id FK)
refresh_tokens (id UUID PK, user_id FK, token_hash, expires_at, revoked, created_at)
```

### notification-service (notification_db)

```sql
notification_logs (id UUID PK, recipient, type, subject, status, sent_at, error_message, created_at)
```

### file-service (file_db)

```sql
files (id UUID PK, user_id, original_name, stored_name, bucket, size, content_type,
       public_file, deleted, created_at)
```

### user-profile-service (profile_db)

```sql
profiles (id VARCHAR PK = auth userId, full_name, avatar_url, bio, phone, address,
          created_at, updated_at)
```

---

## Security Flow

```
1. Client → POST /api/auth/login  (no JWT required, whitelisted in Gateway)
2. auth-service returns { accessToken, refreshToken }
3. Client sends requests with: Authorization: Bearer <accessToken>

4. Gateway GlobalAuthFilter intercepts every non-whitelisted request:
   a. Extract Bearer token from Authorization header
   b. POST /api/auth/validate { token }  →  auth-service
   c. auth-service returns { userId, email, roles }
   d. Gateway adds X-User-Id, X-User-Email, X-User-Roles headers
   e. Forwards request to downstream service

5. Access token: 15 min expiry
6. Refresh token: 7 days, stored as SHA-256 hash in DB
7. Token rotation: every /refresh call issues new refresh token, revokes old one
8. Max 5 active refresh tokens per user (oldest revoked automatically)

Whitelist (no JWT):
  POST /api/auth/register
  POST /api/auth/login
  POST /api/auth/refresh
```

---

## Docker Infrastructure

```yaml
Services in docker-compose.yml:

postgresql:16         port 5432   — 4 databases: auth_db, notification_db, file_db, profile_db
redis:7-alpine        port 6379   — Gateway rate limiting
rabbitmq:3-management ports 5672 (AMQP) + 15672 (Management UI)
minio/minio           ports 9000 (API) + 9001 (Console)
openzipkin/zipkin     port 9411   — Distributed tracing

Startup order (depends_on + service_healthy):
  postgresql → redis → rabbitmq → minio → eureka-server → config-server
  → api-gateway → auth-service → notification-service → file-service → user-profile-service
```

---

## Environment Variables

| Variable | Default | Used By |
|----------|---------|---------|
| POSTGRES_USER | postgres | postgresql |
| POSTGRES_PASSWORD | postgres | all services |
| POSTGRES_HOST | postgresql | all services |
| REDIS_HOST | redis | api-gateway |
| RABBITMQ_HOST | rabbitmq | auth, notification, file, user-profile |
| RABBITMQ_USER | guest | all services |
| RABBITMQ_PASSWORD | guest | all services |
| MINIO_ENDPOINT | http://minio:9000 | file-service |
| MINIO_ACCESS_KEY | minioadmin | file-service |
| MINIO_SECRET_KEY | minioadmin | file-service |
| JWT_SECRET | (set in .env) | auth-service, api-gateway |
| EUREKA_SERVER_URL | http://eureka:secret@eureka-server:8761/eureka | all services |
| CONFIG_SERVER_URL | http://config-server:8888 | all services |
| MAIL_HOST | smtp.gmail.com | notification-service |
| MAIL_USERNAME | (set in .env) | notification-service |
| MAIL_PASSWORD | (set in .env) | notification-service |

Copy `.env.example` → `.env` and fill in secrets before starting.

---

## How to Run Locally

### Prerequisites
- Docker Desktop (running)
- Java 17 + Maven 3.9+ (for building JARs)
- Git

### Step 1: Build all JARs
```bash
cd D:\MICROSERVICE_TEMPLATE
mvn clean package -DskipTests
```

### Step 2: Configure environment
```bash
copy .env.example .env
# Edit .env — at minimum set JWT_SECRET to a 64-char base64 string
```

### Step 3: Start everything
```bash
docker-compose up -d
```

### Step 4: Wait for startup (~90 seconds)
```bash
docker-compose ps         # all services should show "healthy"
```

### Step 5: Verify Eureka dashboard
Open http://localhost:8761 — login: `eureka` / `secret`
Expected: 7 Spring Boot services registered

### Step 6: Smoke tests
```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"Password123!","fullName":"Test User"}'

# Login — copy accessToken from response
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"Password123!"}'

# Get profile (replace TOKEN)
curl http://localhost:8080/api/users/profile \
  -H "Authorization: Bearer TOKEN"

# Upload file (replace TOKEN)
curl -X POST http://localhost:8080/api/files/upload \
  -H "Authorization: Bearer TOKEN" \
  -F "file=@photo.jpg"
```

### Optional: Dev mode (no resource limits)
```bash
docker-compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

### RabbitMQ Management UI
http://localhost:15672 — login: `guest` / `guest`

### MinIO Console
http://localhost:9001 — login: `minioadmin` / `minioadmin`

### Zipkin Traces
http://localhost:9411
