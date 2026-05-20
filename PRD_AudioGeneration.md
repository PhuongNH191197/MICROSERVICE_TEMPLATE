# PRD — Audio Generation Service

## Overview

**Service:** audio-generation-service
**Port:** 8092
**Location:** `business-services/audio-generation-service`

Generate AI audio (clips and full songs) from text prompts using Google Gemini Lyria 3. Async job queue handles long-running generation. Completed audio stored in MinIO via file-service pattern. Events published to RabbitMQ on job completion.

---

## External Integration — Google Gemini Lyria 3

| Property | Value |
|----------|-------|
| Java SDK | `com.google.genai` (Google Gen AI Java SDK) |
| Endpoint | `generateContent` (standard Gemini API) |
| Model — clip | `lyria-3-clip-preview` — ~30s audio |
| Model — song | `lyria-3-pro-preview` — full song |
| Auth | `Authorization: Bearer {GEMINI_API_KEY}` |
| Input | Text prompt + optional image (base64 or URL) |
| Output | MP3/WAV binary, stereo, 48 kHz |

**SDK dependency:**
```xml
<dependency>
    <groupId>com.google.genai</groupId>
    <artifactId>google-genai</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## API Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | /api/audio/generate | Bearer (via Gateway) | Submit generation job, return jobId |
| GET | /api/audio/jobs/{jobId} | Bearer | Poll job status + result URL |
| GET | /api/audio/jobs | Bearer | List user's jobs (paginated) |
| DELETE | /api/audio/jobs/{jobId} | Bearer | Cancel pending job or soft-delete completed |
| GET | /api/audio/{fileId} | Bearer | Redirect to presigned MinIO URL (1h expiry) |

### POST /api/audio/generate — Request Body

```json
{
  "prompt": "Upbeat electronic dance track with heavy bass drops",
  "mode": "CLIP",
  "format": "MP3",
  "imageUrl": "https://...",
  "imageBase64": "...",
  "title": "My Track"
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| prompt | String | Yes | 1–500 chars |
| mode | Enum | Yes | `CLIP` (30s, lyria-3-clip-preview) or `SONG` (full, lyria-3-pro-preview) |
| format | Enum | No | `MP3` (default) or `WAV` |
| imageUrl | String | No | Optional image context — mutually exclusive with imageBase64 |
| imageBase64 | String | No | Optional image context — mutually exclusive with imageUrl |
| title | String | No | Display name, max 200 chars |

### POST /api/audio/generate — Response (202 Accepted)

```json
{
  "success": true,
  "data": {
    "jobId": "uuid",
    "status": "PENDING",
    "mode": "CLIP",
    "estimatedSeconds": 30,
    "createdAt": "2026-05-20T10:00:00Z"
  }
}
```

### GET /api/audio/jobs/{jobId} — Response

```json
{
  "success": true,
  "data": {
    "jobId": "uuid",
    "status": "COMPLETED",
    "mode": "CLIP",
    "format": "MP3",
    "prompt": "...",
    "title": "My Track",
    "fileId": "uuid",
    "audioUrl": "/api/audio/{fileId}",
    "durationSeconds": 29,
    "fileSizeBytes": 1200000,
    "createdAt": "...",
    "completedAt": "..."
  }
}
```

**Job status values:** `PENDING → PROCESSING → COMPLETED | FAILED | CANCELLED`

---

## Business Rules

### Job Lifecycle

- Submit returns `202 Accepted` immediately with `jobId` — never blocks on Gemini call
- Worker picks job from queue, calls Gemini API, streams or buffers audio binary
- On success: upload audio to MinIO `media-audio` bucket, update job record, publish event
- On Gemini failure: retry up to 3× with exponential backoff (5s, 15s, 45s), then mark `FAILED`
- `PENDING` jobs may be cancelled; `PROCESSING` or terminal jobs cannot
- Completed jobs soft-deleted (DB flag); audio file remains in MinIO

### Rate Limiting

- Max 5 concurrent `PROCESSING` jobs per user
- Max 50 total jobs per user (PENDING + PROCESSING); oldest COMPLETED are purgeable
- Gateway-level: 20 requests/minute per IP (inherits platform rate limit)

### Input Validation

- `prompt` required, 1–500 characters
- `mode` required, must be `CLIP` or `SONG`
- `imageUrl` and `imageBase64` mutually exclusive; provide at most one
- `format` defaults to `MP3` if omitted
- `title` optional, max 200 chars, defaults to first 80 chars of prompt if omitted

### Audio Storage

- Upload audio binary to MinIO bucket `media-audio`
- Stored name: `{userId}/{jobId}.{mp3|wav}`
- Presigned URLs valid 1 hour (same pattern as file-service)
- No direct DB sharing with file-service — audio-generation-service manages its own `audio_jobs` table

### Security

- `userId` extracted from `X-User-Id` header (injected by Gateway) — never from request body
- API key (`GEMINI_API_KEY`) from Config Server / env — never hardcoded
- Users may only access their own jobs; return 403 for cross-user access attempts

---

## Data Model

```
audio_jobs(
  id               UUID PK,
  user_id          VARCHAR NOT NULL,
  status           VARCHAR NOT NULL,           -- PENDING|PROCESSING|COMPLETED|FAILED|CANCELLED
  mode             VARCHAR NOT NULL,           -- CLIP|SONG
  format           VARCHAR NOT NULL DEFAULT 'MP3',
  prompt           TEXT NOT NULL,
  title            VARCHAR(200),
  image_url        TEXT,
  gemini_model     VARCHAR,
  file_id          UUID,                       -- set on COMPLETED
  audio_url        TEXT,                       -- presigned redirect path
  duration_seconds INTEGER,
  file_size_bytes  BIGINT,
  retry_count      INTEGER NOT NULL DEFAULT 0,
  error_message    TEXT,
  created_at       TIMESTAMP NOT NULL,
  updated_at       TIMESTAMP NOT NULL,
  completed_at     TIMESTAMP,
  deleted          BOOLEAN NOT NULL DEFAULT FALSE
)
```

**Indexes:** `user_id`, `status`, `created_at DESC`

---

## Async Job Flow

```
Client                   audio-generation-service            Gemini API         MinIO
  |                               |                               |                |
  |-- POST /api/audio/generate -->|                               |                |
  |<-- 202 { jobId, PENDING } ----|                               |                |
  |                               |                               |                |
  |                        [Worker picks job]                     |                |
  |                               |-- generateContent(prompt) -->|                |
  |                               |<---------- audio binary ------|                |
  |                               |                               |                |
  |                               |-- PUT media-audio/{userId}/{jobId}.mp3 ------->|
  |                               |<------------- stored -------------------------|
  |                               |                               |                |
  |                        [Update job: COMPLETED]                |                |
  |                        [Publish audio.generated event]        |                |
  |                               |                               |                |
  |-- GET /api/audio/jobs/{id} -->|                               |                |
  |<-- { status: COMPLETED, audioUrl } ---|                       |                |
```

**Worker implementation:** Spring `@Async` + `ThreadPoolTaskExecutor` (core=2, max=5, queue=100).
On startup, recover any `PROCESSING` jobs stuck from previous crash → reset to `PENDING`.

---

## Events Published

| Exchange | Routing Key | Event | Payload |
|----------|-------------|-------|---------|
| `audio.exchange` | `audio.generated` | `AudioGeneratedEvent` | `{ jobId, userId, title, mode, format, fileId, audioUrl, durationSeconds, prompt }` |
| `audio.exchange` | `audio.failed` | `AudioFailedEvent` | `{ jobId, userId, prompt, errorMessage, retryCount }` |

**Exchange type:** `topic`
**Dead letter queue:** `audio.dlq` (on publish failure)

---

## Events Consumed

None at Phase 2. Future: consume `user.deleted` to purge user's jobs and audio files.

---

## Config Server Entry

File: `infrastructure/config-server/src/main/resources/configs/audio-generation-service.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:audio_db}
    username: ${DB_USER}
    password: ${DB_PASS}
  flyway:
    enabled: true

gemini:
  api-key: ${GEMINI_API_KEY}
  base-url: https://generativelanguage.googleapis.com

minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY}
  secret-key: ${MINIO_SECRET_KEY}
  bucket: media-audio

audio:
  worker:
    core-pool-size: 2
    max-pool-size: 5
    queue-capacity: 100
  job:
    max-per-user: 50
    max-concurrent-per-user: 5
    retry-max: 3
  presigned-url-expiry-hours: 1
```

---

## Gateway Route

Add to `infrastructure/api-gateway/src/main/resources/application.yml`:

```yaml
- id: audio-generation-service
  uri: lb://audio-generation-service
  predicates:
    - Path=/api/audio/**
  filters:
    - name: RequestRateLimiter
      args:
        redis-rate-limiter.replenishRate: 20
        redis-rate-limiter.burstCapacity: 40
```

---

## Docker Compose Addition

```yaml
audio-generation-service:
  build: ./business-services/audio-generation-service
  ports:
    - "8092:8092"
  environment:
    - SPRING_PROFILES_ACTIVE=docker
    - DB_HOST=postgres
    - DB_NAME=audio_db
    - DB_USER=${POSTGRES_USER}
    - DB_PASS=${POSTGRES_PASSWORD}
    - GEMINI_API_KEY=${GEMINI_API_KEY}
    - MINIO_ENDPOINT=http://minio:9000
    - MINIO_ACCESS_KEY=${MINIO_ACCESS_KEY}
    - MINIO_SECRET_KEY=${MINIO_SECRET_KEY}
  depends_on:
    postgres:
      condition: service_healthy
    eureka-server:
      condition: service_healthy
    config-server:
      condition: service_healthy
    rabbitmq:
      condition: service_healthy
    minio:
      condition: service_healthy
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8092/actuator/health"]
    interval: 30s
    timeout: 10s
    retries: 3
```

---

## Dependencies

```xml
<!-- Google Gen AI SDK -->
<dependency>
    <groupId>com.google.genai</groupId>
    <artifactId>google-genai</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Standard — same as user-profile-service -->
<!-- common-dto, common-security, common-events -->
<!-- spring-boot-starter-web, spring-boot-starter-data-jpa -->
<!-- spring-cloud-starter-netflix-eureka-client -->
<!-- spring-cloud-starter-config -->
<!-- spring-rabbit -->
<!-- flyway-core, flyway-database-postgresql -->
<!-- io.minio:minio -->
```

---

## Build Order

Slots after `user-profile-service` (position 11 in root `pom.xml` modules):

```
10. business-services/user-profile-service
11. business-services/audio-generation-service   ← new
```

---

## Error Handling

| HTTP Status | When |
|------------|------|
| 202 | Job accepted |
| 400 | Invalid prompt / bad mode / both imageUrl + imageBase64 provided |
| 401 | Missing or invalid JWT |
| 403 | Job belongs to another user |
| 404 | Job not found |
| 429 | User exceeded max concurrent or total job limits |
| 500 | Unexpected error |

Gemini API errors surface as job `FAILED` with `errorMessage` — never leak raw Gemini response to client.

---

## Done Checklist

### Implementation
- [ ] `audio_jobs` table + Flyway migration
- [ ] `AudioJobService` — submit, poll, list, cancel
- [ ] `GeminiLyriaClient` — wraps `com.google.genai` SDK, handles both models
- [ ] `AudioWorker` — `@Async` executor, retry logic, stuck-job recovery on startup
- [ ] `MinioAudioStorage` — upload binary, generate presigned URL
- [ ] `AudioJobController` — all 5 endpoints
- [ ] `AudioGeneratedEvent`, `AudioFailedEvent` in `common-events`
- [ ] RabbitMQ config: `audio.exchange`, `audio.generated`, `audio.failed`, `audio.dlq`
- [ ] Config Server YAML
- [ ] Gateway route
- [ ] Docker Compose entry
- [ ] `.env.example` addition: `GEMINI_API_KEY`

### Tests
- [ ] `AudioJobServiceTest` — mock GeminiLyriaClient + MinioAudioStorage (Mockito)
- [ ] `GeminiLyriaClientTest` — mock HTTP responses for both models
- [ ] `AudioJobControllerTest` — WebMvcTest, all endpoints
- [ ] Integration test — Testcontainers PostgreSQL + real job lifecycle
