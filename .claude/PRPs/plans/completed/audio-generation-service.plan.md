# Plan: Audio Generation Service (CRBT)

## Summary
Build `audio-generation-service` (port 8092) as a Spring Boot microservice under `business-services/`. The service handles two independent flows sharing a common async job queue: (1) AI CRBT Generator — tags → Gemini Lyria 3 → MP3 clip with Redis cache; (2) DIY TTS Mix — user-uploaded music + ElevenLabs TTS → 3 mixed MP3 versions via FFmpeg. Integrated credit deduction, stuck-job recovery, and RabbitMQ events.

## User Story
As a Mytel subscriber, I want to generate a personalized ringback tone either by selecting mood/genre/instrument tags (AI) or by mixing my own music with a custom voice-over (DIY), so that callers hear my personalized audio when calling me.

## Problem → Solution
No audio generation capability exists → audio-generation-service with two flows, async job tracking, MinIO storage, credit system, and CRBT output (MP3, 40–60s, 48kHz stereo, ≥128kbps).

## Metadata
- **Complexity**: XL
- **Source PRD**: `PRD_AudioGeneration.md` (extended by CRBT-specific requirements in plan prompt)
- **PRD Phase**: New service — Phase 2 business service
- **Estimated Files**: ~35 Java files + 3 SQL migrations + 1 Python extension + config + docker

---

## UX Design

### Flow 1 — AI CRBT Generator
```
User picks:   Genre (6) + Mood (6) + Instrument (6)
                          ↓
Backend:      Redis cache check (hash of 3 tags)
               HIT  → Return audioUrl immediately (1 credit deducted)
               MISS → Create job → 202 + jobId
                          ↓
FE polls:     GET /jobs/{jobId} every 3-5s
                          ↓
COMPLETED:    Display preview player
               [Set as Ringtone] [Download] [Share] [Re-Generate]
```

### Flow 2 — DIY TTS Mix
```
Step 1 — Music:
  Upload via presigned URL OR choose from library
  POST /diy/analyze → vocal check → 3 auto-segments → user picks 1

Step 2 — Voice:
  GET /tts/voices → user picks voice
  User types text (max 100 chars)

Step 3 — Generate:
  POST /diy/generate → 1 credit deducted → 202 + jobId
  FE polls GET /jobs/{jobId} every 3-5s
  COMPLETED → 3 preview versions (Voice Noi Bat / Can Bang / Nhac Noi Bat)
  User picks 1 → [Set as Ringtone] [Download] [Re-Generate]
```

### Interaction Changes
| Touchpoint | Before | After |
|---|---|---|
| POST /api/audio/ai/generate | N/A | 202 + jobId or 200 + cached audioUrl |
| POST /api/audio/diy/analyze | N/A | 200 + {segments[3], hasVocal, bpm, totalDurationMs} |
| GET /api/audio/tts/voices | N/A | 200 + [{voiceId, name, sampleUrl}] |
| POST /api/audio/diy/generate | N/A | 202 + jobId |
| GET /api/audio/jobs/{jobId} | N/A | 200 + full job state incl. previewVersions |
| GET /api/audio/library | N/A | 200 + paginated music library |

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `infra-services/file-service/src/main/java/com/platform/file/service/FileStorageService.java` | all | MinIO upload/presigned URL pattern to replicate |
| P0 | `infra-services/file-service/src/main/java/com/platform/file/config/RabbitMQConfig.java` | all | Exchange/queue/DLQ config to mirror |
| P0 | `business-services/user-profile-service/src/main/java/com/platform/userprofile/listener/UserRegisteredListener.java` | all | @RabbitListener pattern |
| P1 | `infra-services/file-service/src/main/java/com/platform/file/entity/FileRecord.java` | all | Entity structure with status enum |
| P1 | `common/common-events/src/main/java/com/platform/events/FileUploadedEvent.java` | all | Event DTO structure to copy for AudioGeneratedEvent |
| P1 | `common/common-dto/src/main/java/com/platform/dto/ApiResponse.java` | all | Response wrapper used in all controllers |
| P1 | `infra-services/auth-service/src/main/java/com/platform/auth/service/AuthService.java` | 60-70,125-135 | RabbitTemplate.convertAndSend() pattern |
| P2 | `business-services/user-profile-service/pom.xml` | all | pom.xml structure for business service |
| P2 | `infrastructure/config-server/src/main/resources/configs/file-service.yml` | all | Config YAML structure |
| P2 | `business-services/vocal-detector/main.py` | all | Extend with /analyze BPM endpoint |

## External Documentation

| Topic | Key Takeaway |
|---|---|
| Gemini Lyria 3 Java SDK | `GenerativeModel(modelName).generateContent(contents)` returns `GenerateContentResponse` with inline audio bytes (base64) |
| ElevenLabs TTS API | `POST /v1/text-to-speech/{voice_id}` with `xi-api-key` header → binary MP3 stream |
| FFmpeg `loudnorm` | Single-pass: `-filter:a loudnorm=I=-10:LRA=11:TP=-1.5` — two-pass for exact values |
| FFmpeg sidechain ducking | `[voice]asplit[sc][voice];[music][sc]sidechaincompress` then `amix` |
| Essentia BPM | `RhythmExtractor2013` on mono audio → returns bpm float |

---

## Patterns to Mirror

### ENTITY_PATTERN
```java
// SOURCE: infra-services/file-service/entity/FileRecord.java
@Entity @Table(name = "file_records")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FileRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String uploaderId;
    @Enumerated(EnumType.STRING)
    private FileStatus status;
    private boolean deleted;
    @CreationTimestamp private Instant createdAt;
    @UpdateTimestamp private Instant updatedAt;
}
```

### RABBITMQ_CONFIG_PATTERN
```java
// SOURCE: infra-services/file-service/config/RabbitMQConfig.java
@Bean TopicExchange fileExchange() { return new TopicExchange("file.exchange"); }
@Bean Queue fileUploadedQueue() { return QueueBuilder.durable("file.uploaded.queue")
    .withArgument("x-dead-letter-exchange", "file.dlq").build(); }
@Bean Binding fileUploadedBinding() { return BindingBuilder
    .bind(fileUploadedQueue()).to(fileExchange()).with("file.uploaded"); }
```

### EVENT_PUBLISH_PATTERN
```java
// SOURCE: infra-services/auth-service/service/AuthService.java:64-70
rabbitTemplate.convertAndSend(
    "auth.exchange", "user.registered",
    UserRegisteredEvent.builder().userId(user.getId().toString())
        .email(user.getEmail()).occurredAt(Instant.now()).build()
);
```

### RABBIT_LISTENER_PATTERN
```java
// SOURCE: business-services/user-profile-service/listener/UserRegisteredListener.java
@Component @Slf4j
public class UserRegisteredListener {
    @RabbitListener(queues = "user.profile.queue")
    public void handleUserRegistered(UserRegisteredEvent event) {
        log.info("Received UserRegisteredEvent for userId: {}", event.getUserId());
    }
}
```

### CONTROLLER_PATTERN
```java
// SOURCE: infra-services/file-service/controller/FileController.java
@RestController @RequestMapping("/api/files") @RequiredArgsConstructor @Slf4j
public class FileController {
    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<FileUploadResponse>> upload(
        @RequestParam MultipartFile file,
        @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ApiResponse.success(fileStorageService.upload(file, userId)));
    }
}
```

### MINIO_UPLOAD_PATTERN
```java
// SOURCE: infra-services/file-service/service/FileStorageService.java:58-75
minioClient.putObject(PutObjectArgs.builder()
    .bucket(bucket).object(objectName)
    .stream(inputStream, size, -1).contentType(contentType).build());

String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
    .bucket(bucket).object(objectName)
    .method(Method.GET).expiry(1, TimeUnit.HOURS).build());
```

### ASYNC_CONFIG_PATTERN
```java
// No existing example — build from scratch
@Configuration @EnableAsync
public class AsyncConfig {
    @Bean("audioWorkerExecutor")
    public Executor audioWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("audio-worker-");
        executor.initialize();
        return executor;
    }
}
```

### REDIS_CACHE_PATTERN
```java
// No existing per-service cache — build from scratch
// Cache key: "audio:ai:cache:" + sha256(genre + ":" + mood + ":" + instrument)
redisTemplate.opsForValue().set(cacheKey, audioUrl, 30, TimeUnit.DAYS);
String cached = redisTemplate.opsForValue().get(cacheKey);
```

---

## Files to Change / Create

### New: audio-generation-service module

| File | Action | Notes |
|---|---|---|
| `business-services/audio-generation-service/pom.xml` | CREATE | Inherits root parent; adds genai/minio/redis/rabbit deps |
| `pom.xml` (root) | UPDATE | Add module after user-profile-service |
| `AudioGenerationApplication.java` | CREATE | `@SpringBootApplication @EnableDiscoveryClient` |
| `config/AsyncConfig.java` | CREATE | ThreadPoolTaskExecutor bean "audioWorkerExecutor" |
| `config/MinioConfig.java` | CREATE | MinioClient bean + auto-create media-audio bucket |
| `config/RabbitMQConfig.java` | CREATE | audio.exchange + 2 queues (AI, DIY) + DLQ |
| `config/RedisConfig.java` | CREATE | StringRedisTemplate bean |
| `config/WebClientConfig.java` | CREATE | ElevenLabs + VocalDetector WebClient beans |
| `entity/AudioJob.java` | CREATE | Full audio_jobs entity with JSONB previewVersions |
| `entity/UserCredit.java` | CREATE | user_credits entity |
| `entity/CreditTransaction.java` | CREATE | credit_transactions entity |
| `entity/MusicLibrary.java` | CREATE | music_library entity |
| `enums/JobStatus.java` | CREATE | PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED |
| `enums/JobType.java` | CREATE | AI_GENERATE, DIY_MIX |
| `enums/Genre.java` | CREATE | POP, ROCK, CLASSICAL, ELECTRONIC, ACOUSTIC, JAZZ |
| `enums/Mood.java` | CREATE | HAPPY, RELAX, ENERGIZE, ROMANTIC, SAD, MOTIVATION |
| `enums/Instrument.java` | CREATE | PIANO, GUITAR, VIOLIN, SAXOPHONE, DRUMS, FLUTE |
| `repository/AudioJobRepository.java` | CREATE | findByIdAndUserId, findByStatusIn, countByUserIdAndStatusIn |
| `repository/UserCreditRepository.java` | CREATE | findByUserIdForUpdate with PESSIMISTIC_WRITE |
| `repository/MusicLibraryRepository.java` | CREATE | full-text search by title/genre/tags |
| `dto/request/AiGenerateRequest.java` | CREATE | genre (Genre), mood (Mood), instrument (Instrument) — enums |
| `dto/request/DiyAnalyzeRequest.java` | CREATE | fileKey: String |
| `dto/request/DiyGenerateRequest.java` | CREATE | sourceFileKey, segmentStartMs, segmentEndMs, text (max 100), voiceId |
| `dto/response/AudioJobResponse.java` | CREATE | all job fields + previewVersions |
| `dto/response/AnalyzeResponse.java` | CREATE | bpm, totalDurationMs, segments[3], hasVocal |
| `dto/response/TtsVoiceResponse.java` | CREATE | voiceId, name, sampleUrl |
| `dto/response/PreviewVersion.java` | CREATE | version (v1/v2/v3), url, label |
| `service/CreditService.java` | CREATE | deduct (pessimistic lock), refund, getBalance |
| `service/MinioAudioService.java` | CREATE | uploadAudio, generatePresignedGet, downloadToTemp |
| `service/AiGenerateService.java` | CREATE | submit: cache check → credit deduct → job create |
| `service/DiyService.java` | CREATE | analyze, submitDiy, listLibrary, listVoices |
| `service/AudioWorker.java` | CREATE | @RabbitListener x2 + @PostConstruct recovery + retry logic |
| `service/EventPublisher.java` | CREATE | publishGenerated, publishFailed via RabbitTemplate |
| `client/GeminiLyriaClient.java` | CREATE | SDK wrapper, 60s timeout, extract audio bytes |
| `client/ElevenLabsClient.java` | CREATE | WebClient POST to ElevenLabs → binary MP3 |
| `client/VocalDetectorClient.java` | CREATE | WebClient POST /analyze to Python service |
| `processing/FfmpegProcessor.java` | CREATE | cutSegment, mix3Versions, normalizeAudio, padToMinDuration |
| `processing/AudioProcessingException.java` | CREATE | RuntimeException wrapper for FFmpeg errors |
| `controller/AiGenerateController.java` | CREATE | POST /ai/generate, GET /ai/jobs/{id} |
| `controller/DiyController.java` | CREATE | POST /diy/analyze, POST /diy/generate, GET /library |
| `controller/TtsController.java` | CREATE | GET /tts/voices |
| `controller/AudioJobController.java` | CREATE | GET /jobs, DELETE /jobs/{id}, GET /{jobId}, GET /{jobId}/v{n} |
| `exception/GlobalExceptionHandler.java` | CREATE | @RestControllerAdvice covering all exception types |
| `exception/InsufficientCreditException.java` | CREATE | RuntimeException |
| `exception/VocalDetectedException.java` | CREATE | RuntimeException |
| `exception/JobLimitExceededException.java` | CREATE | RuntimeException |
| `converter/PreviewVersionsConverter.java` | CREATE | AttributeConverter List<PreviewVersion> ↔ JSON String |
| `src/main/resources/application.yml` | CREATE | spring.config.import from Config Server |
| `db/migration/V1__create_audio_jobs.sql` | CREATE | audio_jobs table + indexes |
| `db/migration/V2__create_credits.sql` | CREATE | user_credits + credit_transactions |
| `db/migration/V3__create_music_library.sql` | CREATE | music_library + GIN index |
| `Dockerfile` | CREATE | eclipse-temurin:17-jre-alpine + `apk add ffmpeg` |

### Modify: Existing Files

| File | Action | Notes |
|---|---|---|
| `pom.xml` (root) | UPDATE | Add `<module>business-services/audio-generation-service</module>` |
| `infrastructure/api-gateway/.../application.yml` | UPDATE | Add `/api/audio/**` route |
| `infrastructure/config-server/.../configs/audio-generation-service.yml` | CREATE | Full config YAML |
| `docker-compose.yml` | UPDATE | Add audio-generation-service container + audio_db |
| `.env.example` | UPDATE | Add GEMINI_API_KEY, ELEVENLABS_API_KEY |
| `common/common-events/AudioGeneratedEvent.java` | CREATE | New event in common-events module |
| `common/common-events/AudioFailedEvent.java` | CREATE | New event in common-events module |
| `business-services/vocal-detector/main.py` | UPDATE | Add `POST /analyze` endpoint (BPM + segments + vocal) |

## NOT Building
- CRBT registration to CMS Mytone (POST /set-crbt) — stub 501 only; actual CMS integration is separate
- Audio waveform binary data — frontend renders from audio URL
- Credit top-up flow — credit_transactions table ready, top-up API is separate scope
- Music library admin upload UI — direct MinIO + SQL insert for dev
- WebSocket push on job completion — FE polls; no WS needed
- lyria-3-pro-preview (full song mode) — only CLIP (30s) for AI flow in this phase
- ElevenLabs voice cloning — standard voices only

---

## Step-by-Step Tasks

### Task 1: Extend vocal-detector with /analyze endpoint
- **ACTION**: Add BPM detection + vocal check + segment suggestion to Python service
- **IMPLEMENT**:
  ```python
  # business-services/vocal-detector/main.py — add:
  class AnalyzeRequest(BaseModel):
      fileKey: str

  @app.post("/analyze")
  async def analyze(request: AnalyzeRequest):
      audio_mono = load_and_convert_mono(request.fileKey)  # download from MinIO, convert stereo→mono
      has_vocal = classify_vocal(audio_mono)               # existing Essentia classifier
      bpm, _ = es.RhythmExtractor2013()(audio_mono)
      total_ms = len(audio_mono) / SAMPLE_RATE * 1000
      segments = suggest_segments(audio_mono, bpm, total_ms)  # 3x 40-60s at beat boundaries
      return {"hasVocal": bool(has_vocal), "bpm": round(float(bpm), 1),
              "totalDurationMs": int(total_ms), "segments": segments}

  def suggest_segments(audio, bpm, total_ms):
      # segment length: 50s target, round to nearest beat boundary
      beat_interval_ms = 60000 / bpm
      target_ms = 50000
      seg_len = round(target_ms / beat_interval_ms) * beat_interval_ms
      seg_len = max(40000, min(60000, seg_len))
      segments = []
      for i in range(3):
          start = i * (total_ms - seg_len) / 2
          end = min(start + seg_len, total_ms)
          segments.append({"startMs": int(start), "endMs": int(end), "label": f"Segment {i+1}"})
      return segments
  ```
- **GOTCHA**: `RhythmExtractor2013` requires mono float32 array at 44100Hz. Essentia's `MonoLoader` handles conversion. If file is MinIO key, download to temp first.
- **VALIDATE**: `curl -X POST localhost:8765/analyze -H 'Content-Type: application/json' -d '{"fileKey":"test/sample.mp3"}'` → returns bpm > 0, segments.length == 3

### Task 2: Create module scaffold
- **ACTION**: Create directory, pom.xml, Application class
- **IMPLEMENT**:
  - `pom.xml`: parent = root pom, deps include `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-data-redis`, `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-config`, `spring-rabbit`, `flyway-core`, `flyway-database-postgresql`, `io.minio:minio:8.5.9`, `com.google.genai:google-genai:1.0.0`, `spring-boot-starter-validation`, `lombok`, `common-dto`, `common-security`, `common-events`
  - Add Google Maven repo if `com.google.genai` not on Central
  - `AudioGenerationApplication.java`: `@SpringBootApplication @EnableDiscoveryClient`
  - Add module to root `pom.xml` after `user-profile-service`
- **GOTCHA**: `com.google.genai:google-genai` may require Google's Maven repo:
  ```xml
  <repository>
    <id>google-oss</id>
    <url>https://google.oss.sonatype.org/content/repositories/releases/</url>
  </repository>
  ```
- **VALIDATE**: `mvn clean compile -pl business-services/audio-generation-service` → BUILD SUCCESS

### Task 3: Add events to common-events
- **ACTION**: 2 new event classes
- **IMPLEMENT**:
  ```java
  // AudioGeneratedEvent.java
  @Data @Builder @NoArgsConstructor @AllArgsConstructor
  public class AudioGeneratedEvent {
      private String jobId, userId, jobType, title, audioUrl;
      private List<String> previewUrls;  // DIY: 3 URLs; AI: single URL in list
      private Integer durationSeconds;
      @Builder.Default private Instant occurredAt = Instant.now();
  }

  // AudioFailedEvent.java
  @Data @Builder @NoArgsConstructor @AllArgsConstructor
  public class AudioFailedEvent {
      private String jobId, userId, errorMessage;
      private Integer retryCount;
      @Builder.Default private Instant occurredAt = Instant.now();
  }
  ```
- **MIRROR**: FileUploadedEvent structure
- **VALIDATE**: `mvn clean compile -pl common/common-events` → BUILD SUCCESS

### Task 4: Flyway migrations (3 files)
- **ACTION**: Create SQL schema files

  `V1__create_audio_jobs.sql`:
  ```sql
  CREATE TABLE audio_jobs (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id VARCHAR(255) NOT NULL,
      job_type VARCHAR(50) NOT NULL,
      status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
      genre VARCHAR(50), mood VARCHAR(50), instrument VARCHAR(50),
      mode VARCHAR(20) DEFAULT 'CLIP',
      source_file_key VARCHAR(500),
      segment_start_ms INTEGER, segment_end_ms INTEGER,
      tts_text VARCHAR(100), voice_id VARCHAR(100),
      format VARCHAR(10) NOT NULL DEFAULT 'MP3',
      prompt TEXT, title VARCHAR(200),
      file_id UUID, audio_url TEXT,
      preview_versions JSONB,
      cache_key VARCHAR(255),
      duration_seconds INTEGER, file_size_bytes BIGINT,
      retry_count INTEGER NOT NULL DEFAULT 0,
      error_message TEXT, deleted BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMP NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
      completed_at TIMESTAMP
  );
  CREATE INDEX idx_audio_jobs_user_id ON audio_jobs(user_id);
  CREATE INDEX idx_audio_jobs_status ON audio_jobs(status);
  CREATE INDEX idx_audio_jobs_created ON audio_jobs(created_at DESC);
  ```

  `V2__create_credits.sql`:
  ```sql
  CREATE TABLE user_credits (
      user_id VARCHAR(255) PRIMARY KEY,
      balance INTEGER NOT NULL DEFAULT 0,
      updated_at TIMESTAMP NOT NULL DEFAULT NOW()
  );
  CREATE TABLE credit_transactions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id VARCHAR(255) NOT NULL,
      amount INTEGER NOT NULL,
      type VARCHAR(50) NOT NULL,
      job_id UUID,
      created_at TIMESTAMP NOT NULL DEFAULT NOW()
  );
  CREATE INDEX idx_credit_tx_user ON credit_transactions(user_id, created_at DESC);
  ```

  `V3__create_music_library.sql`:
  ```sql
  CREATE TABLE music_library (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      title VARCHAR(255) NOT NULL, artist VARCHAR(255),
      genre VARCHAR(100), duration_ms INTEGER,
      file_key VARCHAR(500) NOT NULL,
      bucket VARCHAR(100) NOT NULL DEFAULT 'media-audio',
      tags TEXT[],
      active BOOLEAN NOT NULL DEFAULT TRUE,
      created_at TIMESTAMP NOT NULL DEFAULT NOW()
  );
  CREATE INDEX idx_music_library_fts ON music_library
      USING GIN(to_tsvector('simple',
          title || ' ' || COALESCE(artist,'') || ' ' || COALESCE(genre,'')));
  ```
- **GOTCHA**: `JSONB` requires PostgreSQL ≥ 9.4 (guaranteed). `TEXT[]` arrays need `@Column(columnDefinition = "text[]")` + custom converter or Hibernate array type.

### Task 5: Config beans
- **ACTION**: Create 5 config classes following existing patterns
- **IMPLEMENT**:
  - `AsyncConfig.java` — `@Configuration @EnableAsync`, bean `"audioWorkerExecutor"`, core=2, max=5, queue=100
  - `MinioConfig.java` — mirror file-service MinioConfig exactly; auto-create `media-audio` bucket
  - `RabbitMQConfig.java` — `audio.exchange` (topic), `audio.ai.generate.queue`, `audio.diy.mix.queue`, `audio.dlq` with x-dead-letter-exchange
  - `RedisConfig.java` — `@Bean StringRedisTemplate`
  - `WebClientConfig.java` — 2 beans: `elevenLabsWebClient` (baseUrl + xi-api-key header), `vocalDetectorWebClient` (baseUrl only)
- **MIRROR**: RABBITMQ_CONFIG_PATTERN, ASYNC_CONFIG_PATTERN

### Task 6: Entities and repositories
- **ACTION**: 4 entities + 3 repositories
- **IMPLEMENT**:
  - `AudioJob.java` — map V1 migration. `previewVersions` field: `@Column(columnDefinition = "jsonb") @Convert(converter = PreviewVersionsConverter.class) List<PreviewVersion>`
  - `UserCredit.java` — `@Version` for OL
  - `PreviewVersionsConverter.java` — `AttributeConverter<List<PreviewVersion>, String>` using `ObjectMapper`
  - `AudioJobRepository`:
    ```java
    Optional<AudioJob> findByIdAndUserId(UUID id, String userId);
    List<AudioJob> findByStatus(JobStatus status);  // startup recovery
    long countByUserIdAndStatusIn(String userId, List<JobStatus> statuses);
    Page<AudioJob> findByUserIdAndDeletedFalse(String userId, Pageable p);
    ```
  - `UserCreditRepository`:
    ```java
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM UserCredit c WHERE c.userId = :userId")
    Optional<UserCredit> findByUserIdForUpdate(@Param("userId") String userId);
    ```
- **MIRROR**: ENTITY_PATTERN
- **GOTCHA**: Pessimistic lock requires the method to be called within a `@Transactional` boundary on a Spring-managed proxy. Do NOT call via `this.method()`.

### Task 7: CreditService
- **ACTION**: Thread-safe credit deduction with pessimistic lock
- **IMPLEMENT**:
  ```java
  @Service @RequiredArgsConstructor @Slf4j
  public class CreditService {
      @Transactional
      public void deductCredit(String userId, UUID jobId) {
          UserCredit credit = repo.findByUserIdForUpdate(userId)
              .orElseThrow(() -> new InsufficientCreditException("No credit account for user"));
          if (credit.getBalance() < 1)
              throw new InsufficientCreditException("Insufficient credits (balance=0)");
          credit.setBalance(credit.getBalance() - 1);
          credit.setUpdatedAt(Instant.now());
          repo.save(credit);
          txRepo.save(CreditTransaction.builder()
              .userId(userId).amount(-1).type("DEBIT_GENERATION").jobId(jobId).build());
          log.info("Deducted 1 credit userId={} newBalance={}", userId, credit.getBalance());
      }

      @Transactional
      public void refundCredit(String userId, UUID jobId) {
          UserCredit credit = repo.findByUserIdForUpdate(userId)
              .orElseGet(() -> UserCredit.builder().userId(userId).balance(0).build());
          credit.setBalance(credit.getBalance() + 1);
          credit.setUpdatedAt(Instant.now());
          repo.save(credit);
          txRepo.save(CreditTransaction.builder()
              .userId(userId).amount(+1).type("REFUND_CANCEL").jobId(jobId).build());
          log.info("Refunded 1 credit userId={} newBalance={}", userId, credit.getBalance());
      }
  }
  ```
- **GOTCHA**: `InsufficientCreditException` must be a `RuntimeException` so `@Transactional` rolls back on throw.

### Task 8: MinioAudioService
- **ACTION**: Upload bytes, generate presigned URLs, download to temp
- **IMPLEMENT**:
  ```java
  public String uploadAudio(byte[] audioBytes, String userId, String objectSuffix, String contentType) {
      String objectName = userId + "/" + objectSuffix;
      minioClient.putObject(PutObjectArgs.builder()
          .bucket("media-audio").object(objectName)
          .stream(new ByteArrayInputStream(audioBytes), audioBytes.length, -1)
          .contentType(contentType).build());
      return objectName;
  }

  public String generatePresignedGet(String objectName) {
      return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
          .bucket("media-audio").object(objectName)
          .method(Method.GET).expiry(1, TimeUnit.HOURS).build());
  }

  public Path downloadToTemp(String fileKey) throws Exception {
      Path tmp = Files.createTempFile("source_", ".mp3");
      try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
              .bucket("media-audio").object(fileKey).build())) {
          Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
      }
      return tmp;
  }
  ```
- **MIRROR**: MINIO_UPLOAD_PATTERN

### Task 9: GeminiLyriaClient
- **ACTION**: SDK wrapper for Lyria 3 clip generation
- **IMPLEMENT**:
  ```java
  @Component @Slf4j
  public class GeminiLyriaClient {
      @Value("${gemini.api-key}") private String apiKey;

      public byte[] generateClip(String prompt) {
          try {
              Client client = new Client(new ClientConfig()
                  .setApiKey(apiKey)
                  .setTimeout(Duration.ofSeconds(60)));
              GenerateContentResponse resp = client.models
                  .generateContent("lyria-3-clip-preview",
                      List.of(Content.fromText(prompt)), null);
              Part part = resp.candidates().get(0).content().parts().get(0);
              // SDK may return base64 string or raw bytes — handle both
              if (part.inlineData() != null) {
                  String b64 = part.inlineData().data();
                  return Base64.getDecoder().decode(b64);
              }
              throw new AudioProcessingException("Gemini returned no audio data");
          } catch (Exception e) {
              throw new AudioProcessingException("Gemini Lyria call failed: " + e.getMessage(), e);
          }
      }
  }
  ```
- **GOTCHA**: Lyria 3 audio output may be shorter than 40s minimum for CMS Mytone. Always run `padToMinDuration(audioBytes, 40)` in FfmpegProcessor after generation.
- **GOTCHA**: The SDK response shape for Lyria is NOT the same as Gemini text. Verify with a real API call in integration test before assuming `inlineData().data()` is the path.

### Task 10: ElevenLabsClient
- **ACTION**: WebClient call to ElevenLabs TTS REST API
- **IMPLEMENT**:
  ```java
  @Component @RequiredArgsConstructor @Slf4j
  public class ElevenLabsClient {
      @Qualifier("elevenLabsWebClient") private final WebClient webClient;

      public byte[] synthesize(String text, String voiceId) {
          log.info("TTS synthesis voiceId={} textLen={}", voiceId, text.length());
          return webClient.post()
              .uri("/v1/text-to-speech/" + voiceId)
              .header("Accept", "audio/mpeg")
              .bodyValue(Map.of(
                  "text", text,
                  "model_id", "eleven_multilingual_v2",
                  "voice_settings", Map.of("stability", 0.5, "similarity_boost", 0.75)
              ))
              .retrieve()
              .onStatus(status -> status.is4xxClientError(),
                  r -> r.bodyToMono(String.class).map(b -> new AudioProcessingException("ElevenLabs 4xx: " + b)))
              .bodyToMono(byte[].class)
              .block(Duration.ofSeconds(30));
      }

      public List<TtsVoiceResponse> listVoices() {
          // GET /v1/voices, parse items[].voice_id and .name and .preview_url
          Map<?, ?> resp = webClient.get().uri("/v1/voices").retrieve()
              .bodyToMono(Map.class).block(Duration.ofSeconds(10));
          // parse and map to TtsVoiceResponse
      }
  }
  ```

### Task 11: VocalDetectorClient
- **ACTION**: REST call to Python service (not Eureka-registered — use URL from config)
- **IMPLEMENT**:
  ```java
  @Component @RequiredArgsConstructor
  public class VocalDetectorClient {
      @Qualifier("vocalDetectorWebClient") private final WebClient webClient;

      public AnalyzeResult analyze(String fileKey) {
          return webClient.post().uri("/analyze")
              .bodyValue(Map.of("fileKey", fileKey))
              .retrieve()
              .bodyToMono(AnalyzeResult.class)
              .block(Duration.ofSeconds(30));
      }
  }
  ```
- **GOTCHA**: vocal-detector is Python FastAPI — not Spring, not Eureka. Config: `vocal-detector.url=http://vocal-detector:8765` in docker; `http://localhost:8765` for local dev.

### Task 12: FfmpegProcessor
- **ACTION**: FFmpeg audio processing via ProcessBuilder (no extra Java dep)
- **IMPLEMENT**:
  ```java
  @Component @Slf4j
  public class FfmpegProcessor {

      public Path cutSegment(Path input, int startMs, int endMs) throws IOException, InterruptedException {
          Path out = Files.createTempFile("cut_", ".mp3");
          run("ffmpeg", "-y", "-i", input.toString(),
              "-ss", msToSec(startMs), "-to", msToSec(endMs),
              "-ar", "48000", "-ac", "2", "-b:a", "128k", out.toString());
          return out;
      }

      // Returns 3 paths: v1 (voice dominant), v2 (balanced), v3 (music dominant)
      public List<Path> mix3Versions(Path voicePath, Path musicPath) throws IOException, InterruptedException {
          int[][] configs = {{-10, -20}, {-10, -18}, {-12, -16}};  // {voiceLUFS, musicLUFS}
          List<Path> results = new ArrayList<>();
          for (int[] cfg : configs) {
              Path out = Files.createTempFile("mix_", ".mp3");
              // Normalize voice to cfg[0] LUFS, music to cfg[1] LUFS, then mix with ducking
              String voiceFilter = "loudnorm=I=" + cfg[0] + ":LRA=11:TP=-1.5";
              String musicFilter = "loudnorm=I=" + cfg[1] + ":LRA=11:TP=-1.5";
              // Simple mix (ducking requires more complex filter graph — see note below)
              run("ffmpeg", "-y",
                  "-i", voicePath.toString(),
                  "-i", musicPath.toString(),
                  "-filter_complex",
                  "[0:a]" + voiceFilter + "[v];[1:a]" + musicFilter + "[m];" +
                  "[m][v]sidechaincompress=threshold=0.02:ratio=4:attack=5:release=200[mc];" +
                  "[mc][v]amix=inputs=2:duration=longest[out];" +
                  "[out]afade=t=in:st=0:d=0.3,afade=t=out:st=" + (50 - 0.5) + ":d=0.5",
                  "-ar", "48000", "-ac", "2", "-b:a", "128k", out.toString());
              results.add(out);
          }
          return results;
      }

      public Path padToMinDuration(Path input, int minSeconds) throws IOException, InterruptedException {
          Path out = Files.createTempFile("padded_", ".mp3");
          run("ffmpeg", "-y", "-i", input.toString(),
              "-filter_complex",
              "apad=pad_dur=" + minSeconds,
              "-t", String.valueOf(minSeconds),
              "-ar", "48000", "-ac", "2", "-b:a", "128k", out.toString());
          return out;
      }

      private void run(String... cmd) throws IOException, InterruptedException {
          Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
          String output = new String(p.getInputStream().readAllBytes());
          int exit = p.waitFor();
          if (exit != 0) throw new AudioProcessingException("ffmpeg exit=" + exit + " output=" + output);
          log.debug("ffmpeg OK: {}", String.join(" ", cmd));
      }

      private String msToSec(int ms) { return String.format("%.3f", ms / 1000.0); }
  }
  ```
- **GOTCHA**: `sidechaincompress` may not be available in all ffmpeg builds. Test in Docker container. Alternative: use `volume` filter with `agate` or manual volume envelope via `asendcmd`.
- **GOTCHA**: Temp files MUST be deleted after MinIO upload. Wrap in `try-finally`.
- **GOTCHA**: FFmpeg must be installed in Docker. Dockerfile: `RUN apk add --no-cache ffmpeg` (alpine) or `apt-get install -y ffmpeg` (debian).

### Task 13: AiGenerateService
- **ACTION**: Flow 1 submit — cache first, credit deduct, job create
- **IMPLEMENT**:
  ```java
  @Service @RequiredArgsConstructor @Slf4j
  public class AiGenerateService {

      @Transactional
      public AudioJobResponse submit(AiGenerateRequest req, String userId) {
          String cacheKey = buildCacheKey(req);

          // Always deduct credit on ACCEPTED (even on cache hit per spec)
          creditService.deductCredit(userId, null);

          String cachedUrl = redisTemplate.opsForValue().get(cacheKey);
          if (cachedUrl != null) {
              log.info("Cache HIT cacheKey={} userId={}", cacheKey, userId);
              // Create synthetic COMPLETED job for history
              AudioJob job = AudioJob.builder()
                  .userId(userId).jobType(JobType.AI_GENERATE).status(JobStatus.COMPLETED)
                  .genre(req.getGenre().name()).mood(req.getMood().name())
                  .instrument(req.getInstrument().name())
                  .audioUrl(cachedUrl).cacheKey(cacheKey)
                  .completedAt(Instant.now()).build();
              return toResponse(jobRepo.save(job));
          }

          String prompt = buildPrompt(req);
          AudioJob job = AudioJob.builder()
              .userId(userId).jobType(JobType.AI_GENERATE).status(JobStatus.PENDING)
              .genre(req.getGenre().name()).mood(req.getMood().name())
              .instrument(req.getInstrument().name()).mode("CLIP")
              .prompt(prompt).title(buildTitle(req)).cacheKey(cacheKey).build();
          job = jobRepo.save(job);
          rabbitTemplate.convertAndSend(RabbitMQConfig.AUDIO_EXCHANGE,
              "audio.ai.generate", job.getId().toString());
          log.info("Queued AI job id={} userId={}", job.getId(), userId);
          return toResponse(job);  // status=PENDING
      }

      private String buildPrompt(AiGenerateRequest req) {
          return String.format(
              "Generate a 30-second %s instrumental music piece with %s mood, " +
              "featuring %s as the lead instrument. No vocals. 48kHz stereo. Radio quality.",
              req.getGenre().getLabel(), req.getMood().getLabel(), req.getInstrument().getLabel()
          );
      }

      private String buildCacheKey(AiGenerateRequest req) {
          String raw = req.getGenre() + ":" + req.getMood() + ":" + req.getInstrument();
          return "audio:ai:cache:" + sha256Hex(raw);
      }
  }
  ```

### Task 14: DiyService
- **ACTION**: Flow 2 analyze + submit
- **IMPLEMENT**:
  ```java
  @Service @RequiredArgsConstructor @Slf4j
  public class DiyService {

      public AnalyzeResponse analyze(DiyAnalyzeRequest req, String userId) {
          AnalyzeResult result = vocalDetectorClient.analyze(req.getFileKey());
          if (result.isHasVocal()) {
              throw new VocalDetectedException(
                  "Music contains vocals. Please use an instrumental track.");
          }
          return AnalyzeResponse.builder()
              .bpm(result.getBpm()).totalDurationMs(result.getTotalDurationMs())
              .segments(result.getSegments()).hasVocal(false).build();
      }

      @Transactional
      public AudioJobResponse submitDiy(DiyGenerateRequest req, String userId) {
          long active = jobRepo.countByUserIdAndStatusIn(userId,
              List.of(JobStatus.PENDING, JobStatus.PROCESSING));
          if (active >= 5) throw new JobLimitExceededException("Max 5 concurrent jobs per user");

          creditService.deductCredit(userId, null);

          AudioJob job = AudioJob.builder()
              .userId(userId).jobType(JobType.DIY_MIX).status(JobStatus.PENDING)
              .sourceFileKey(req.getSourceFileKey())
              .segmentStartMs(req.getSegmentStartMs()).segmentEndMs(req.getSegmentEndMs())
              .ttsText(req.getText()).voiceId(req.getVoiceId()).format("MP3").build();
          job = jobRepo.save(job);
          rabbitTemplate.convertAndSend(RabbitMQConfig.AUDIO_EXCHANGE,
              "audio.diy.mix", job.getId().toString());
          log.info("Queued DIY job id={} userId={}", job.getId(), userId);
          return toResponse(job);
      }

      public Page<MusicLibrary> listLibrary(String query, Pageable pageable) {
          if (query == null || query.isBlank()) return musicLibraryRepo.findByActiveTrue(pageable);
          return musicLibraryRepo.searchByText(query, pageable);
      }

      public List<TtsVoiceResponse> listVoices() {
          return elevenLabsClient.listVoices();
      }
  }
  ```

### Task 15: AudioWorker (core async processor)
- **ACTION**: @RabbitListener x2 + @PostConstruct recovery + retry logic
- **IMPLEMENT**:
  ```java
  @Component @RequiredArgsConstructor @Slf4j
  public class AudioWorker {

      @PostConstruct
      public void recoverStuckJobs() {
          List<AudioJob> stuck = jobRepo.findByStatus(JobStatus.PROCESSING);
          for (AudioJob j : stuck) {
              j.setStatus(JobStatus.PENDING); j.setUpdatedAt(Instant.now());
              jobRepo.save(j);
          }
          if (!stuck.isEmpty())
              log.warn("Recovered {} stuck PROCESSING jobs on startup", stuck.size());
      }

      @RabbitListener(queues = RabbitMQConfig.AI_GENERATE_QUEUE)
      public void onAiJob(String jobId) { process(UUID.fromString(jobId), this::runAiJob); }

      @RabbitListener(queues = RabbitMQConfig.DIY_MIX_QUEUE)
      public void onDiyJob(String jobId) { process(UUID.fromString(jobId), this::runDiyJob); }

      private void process(UUID jobId, Consumer<AudioJob> runner) {
          AudioJob job = jobRepo.findById(jobId).orElseThrow();
          job.setStatus(JobStatus.PROCESSING); job.setUpdatedAt(Instant.now());
          jobRepo.save(job);
          try {
              runner.accept(job);
          } catch (Exception e) {
              log.error("Job {} failed attempt={}: {}", jobId, job.getRetryCount() + 1, e.getMessage());
              job.setRetryCount(job.getRetryCount() + 1);
              if (job.getRetryCount() >= 3) {
                  job.setStatus(JobStatus.FAILED);
                  job.setErrorMessage("Max retries exceeded");
                  jobRepo.save(job);
                  eventPublisher.publishFailed(job);
                  creditService.refundCredit(job.getUserId(), job.getId());
              } else {
                  int[] delays = {5000, 15000, 45000};
                  try { Thread.sleep(delays[job.getRetryCount() - 1]); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                  job.setStatus(JobStatus.PENDING); jobRepo.save(job);
                  // Re-queue
                  String queue = job.getJobType() == JobType.AI_GENERATE
                      ? RabbitMQConfig.AI_GENERATE_QUEUE : RabbitMQConfig.DIY_MIX_QUEUE;
                  rabbitTemplate.convertAndSend(RabbitMQConfig.AUDIO_EXCHANGE,
                      job.getJobType() == JobType.AI_GENERATE ? "audio.ai.generate" : "audio.diy.mix",
                      job.getId().toString());
              }
          }
      }

      private void runAiJob(AudioJob job) throws Exception {
          byte[] raw = geminiClient.generateClip(job.getPrompt());
          // Pad to 40s minimum
          Path padded = ffmpegProcessor.padToMinDuration(writeTempFile(raw), 40);
          byte[] audio = Files.readAllBytes(padded);
          Files.deleteIfExists(padded);
          String objectName = minioService.uploadAudio(audio, job.getUserId(),
              job.getId() + ".mp3", "audio/mpeg");
          String presigned = minioService.generatePresignedGet(objectName);
          // Cache result
          if (job.getCacheKey() != null)
              redisTemplate.opsForValue().set(job.getCacheKey(), presigned, 30, TimeUnit.DAYS);
          job.setStatus(JobStatus.COMPLETED);
          job.setAudioUrl("/api/audio/" + job.getId());
          job.setCompletedAt(Instant.now());
          job.setFileSizeBytes((long) audio.length);
          jobRepo.save(job);
          eventPublisher.publishGenerated(job);
      }

      private void runDiyJob(AudioJob job) throws Exception {
          // 1. TTS
          byte[] voiceBytes = elevenLabsClient.synthesize(job.getTtsText(), job.getVoiceId());
          Path voicePath = writeTempFile(voiceBytes);
          // 2. Download source music
          Path musicPath = minioService.downloadToTemp(job.getSourceFileKey());
          // 3. Cut segment
          Path cutPath = ffmpegProcessor.cutSegment(musicPath, job.getSegmentStartMs(), job.getSegmentEndMs());
          // 4. Mix 3 versions
          List<Path> mixed = ffmpegProcessor.mix3Versions(voicePath, cutPath);
          // 5. Upload 3 files
          String[] labels = {"Voice Noi Bat", "Can Bang", "Nhac Noi Bat"};
          List<PreviewVersion> versions = new ArrayList<>();
          for (int i = 0; i < 3; i++) {
              byte[] bytes = Files.readAllBytes(mixed.get(i));
              String key = minioService.uploadAudio(bytes, job.getUserId(),
                  job.getId() + "_v" + (i+1) + ".mp3", "audio/mpeg");
              versions.add(new PreviewVersion("v" + (i+1),
                  "/api/audio/" + job.getId() + "/v" + (i+1), labels[i]));
          }
          // 6. Cleanup temps
          for (Path p : List.of(voicePath, musicPath, cutPath)) Files.deleteIfExists(p);
          for (Path p : mixed) Files.deleteIfExists(p);
          // 7. Update job
          job.setStatus(JobStatus.COMPLETED);
          job.setPreviewVersions(versions);
          job.setCompletedAt(Instant.now());
          jobRepo.save(job);
          eventPublisher.publishGenerated(job);
      }

      private Path writeTempFile(byte[] bytes) throws IOException {
          Path p = Files.createTempFile("audio_", ".mp3");
          Files.write(p, bytes); return p;
      }
  }
  ```
- **GOTCHA**: `Thread.sleep()` blocks RabbitMQ listener thread. For production, use TTL-based delayed re-queue. Acceptable for 3-retry small-scale use.
- **GOTCHA**: Do NOT annotate `@RabbitListener` methods with `@Async` — double-threading causes transaction issues.

### Task 16: Controllers (4 classes)
- **ACTION**: REST endpoints per spec
- **IMPLEMENT** — all follow CONTROLLER_PATTERN. Key notes:
  - `@RequestHeader("X-User-Id") String userId` on every method that needs identity
  - `AiGenerateController.submit()` returns `ResponseEntity.accepted()` (202) when PENDING, `ResponseEntity.ok()` (200) when cache hit
  - `DiyController.analyze()` catches `VocalDetectedException` via `GlobalExceptionHandler` → 400
  - `AudioJobController.cancel()`: check status = PENDING; if yes → CANCELLED + refund credit; else 409
  - Presigned URL redirect: `return ResponseEntity.status(302).header("Location", presignedUrl).build()`

### Task 17: GlobalExceptionHandler + Config + Docker
- **ACTION**: Exception handler + infrastructure wiring

  `GlobalExceptionHandler`:
  - `InsufficientCreditException` → 429
  - `VocalDetectedException` → 400
  - `JobLimitExceededException` → 429
  - `EntityNotFoundException` → 404
  - `AccessDeniedException` → 403
  - `MethodArgumentNotValidException` → 400
  - `Exception` → 500 (log full stack, return generic message)

  Config Server YAML, Gateway route, Docker Compose, .env.example — per Files to Change section above.

  Dockerfile:
  ```dockerfile
  FROM eclipse-temurin:17-jre-alpine
  RUN apk add --no-cache ffmpeg
  COPY target/audio-generation-service-*.jar app.jar
  EXPOSE 8092
  ENTRYPOINT ["java", "-jar", "/app.jar"]
  ```

### Task 18: Tests
- **ACTION**: Unit tests + 1 integration test
- **IMPLEMENT**:

  `AiGenerateServiceTest` (Mockito):
  - mock `creditService`, `redisTemplate`, `jobRepo`, `rabbitTemplate`
  - `submit_cacheHit`: verify COMPLETED job saved, credit deducted, rabbitTemplate NOT called
  - `submit_cacheMiss`: verify PENDING job saved, rabbitTemplate.convertAndSend called once
  - `submit_noCredit`: creditService throws `InsufficientCreditException` → propagates

  `DiyServiceTest` (Mockito):
  - `analyze_vocal`: vocalDetectorClient returns hasVocal=true → `VocalDetectedException`
  - `analyze_instrumental`: returns segments, bpm
  - `submitDiy_limitReached`: countByUserIdAndStatusIn returns 5 → `JobLimitExceededException`

  `CreditServiceTest` (Testcontainers PostgreSQL):
  - `deduct_success`, `deduct_insufficient`, `refund`

  `AudioWorkerTest` (Mockito):
  - `onAiJob_success`: gemini returns bytes → minio upload → job COMPLETED → event published
  - `onAiJob_geminiFailure_retries`: first 2 calls throw → retryCount increments → PENDING re-queue
  - `onAiJob_maxRetries_failsAndRefunds`: 3rd attempt throws → FAILED → refundCredit called

---

## Testing Strategy

### Unit Tests

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| submit AI — cache HIT | genre=POP, mood=HAPPY, cached=true | status=COMPLETED, no queue publish | No |
| submit AI — cache MISS | genre=POP, mood=HAPPY, cached=false | status=PENDING, queue published | No |
| submit AI — 0 credits | balance=0 | InsufficientCreditException (429) | Yes |
| analyze DIY — vocal | hasVocal=true | VocalDetectedException (400) | Yes |
| submitDiy — 5 active jobs | count=5 | JobLimitExceededException (429) | Yes |
| worker AI — success | gemini OK | COMPLETED, cache set, event published | No |
| worker AI — retry → max | 3x throw | FAILED, credit refunded | Yes |
| cancel PENDING job | status=PENDING | CANCELLED, credit refunded | No |
| cancel PROCESSING job | status=PROCESSING | 409 Conflict | Yes |
| access other user's job | different userId | 403 Forbidden | Yes |

### Edge Cases Checklist
- [ ] Empty prompt (validate @NotBlank)
- [ ] ttsText over 100 chars (validate @Size)
- [ ] segmentEndMs <= segmentStartMs (validate @AssertTrue)
- [ ] MinIO upload failure — job stays FAILED, temp files cleaned up
- [ ] FFmpeg binary not found — IOException → AudioProcessingException → FAILED
- [ ] Gemini 401 (bad API key) — NOT retried, mark FAILED immediately
- [ ] Service restart with PROCESSING jobs — @PostConstruct resets to PENDING
- [ ] Concurrent credit deduction — pessimistic lock prevents negative balance

---

## Validation Commands

### Compile
```bash
mvn clean compile -pl business-services/audio-generation-service
```
EXPECT: BUILD SUCCESS

### Full Build
```bash
mvn clean package -DskipTests
```
EXPECT: All 12 modules BUILD SUCCESS

### Unit Tests
```bash
mvn test -pl business-services/audio-generation-service
```
EXPECT: All tests pass

### Service Health
```bash
docker-compose up -d audio-generation-service
curl http://localhost:8092/actuator/health
```
EXPECT: `{"status":"UP"}`

### Flow 1 Smoke Test
```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@test.com","password":"P@ssword1"}' | jq -r '.data.accessToken')

JOB_ID=$(curl -s -X POST localhost:8080/api/audio/ai/generate \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"genre":"POP","mood":"HAPPY","instrument":"PIANO"}' | jq -r '.data.jobId')

# Poll until COMPLETED
until [ "$(curl -s localhost:8080/api/audio/jobs/$JOB_ID \
  -H "Authorization: Bearer $TOKEN" | jq -r '.data.status')" = "COMPLETED" ]; do
  sleep 5; done
echo "Job completed"
```

### Flow 2 Smoke Test
```bash
# Analyze music file
curl -X POST localhost:8080/api/audio/diy/analyze \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"fileKey":"library/sample_instrumental.mp3"}'
# EXPECT: {bpm, segments[3], hasVocal: false}
```

---

## Acceptance Criteria
- [ ] POST /api/audio/ai/generate → 202 + jobId (or 200 + audioUrl on cache hit)
- [ ] Job COMPLETED with valid MP3 in MinIO media-audio bucket
- [ ] Identical AI tag request within 30 days returns cached URL without Gemini call
- [ ] POST /api/audio/diy/analyze → 400 for vocal music
- [ ] POST /api/audio/diy/generate → 3 LUFS-normalized mixed MP3 versions
- [ ] Failed jobs retry 3×; credit refunded on final failure
- [ ] @PostConstruct recovers PROCESSING jobs to PENDING on restart
- [ ] userId never from request body — always X-User-Id header
- [ ] All 12 modules BUILD SUCCESS
- [ ] All unit tests pass

---

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Gemini Lyria SDK response shape differs | High | High | Integration test against real API key on day 1 of impl |
| `com.google.genai` not on Maven Central | Medium | Medium | Check Google Maven repo; fallback to Gemini REST API |
| FFmpeg `sidechaincompress` not in alpine build | Medium | High | Test in Docker early; fallback to `volume` filter ducking |
| Lyria output < 40s (CMS Mytone minimum) | High | Medium | `padToMinDuration` pads with silence or musical fade |
| ElevenLabs rate limits on free tier | Medium | Medium | Cache TTS bytes in Redis 24h by (text+voiceId) hash |
| Pessimistic lock deadlock on credits | Low | High | Short `@Transactional` scope; set `javax.persistence.lock.timeout=3000` |
| vocal-detector Python service cold start | Low | Low | Add healthcheck + depends_on in docker-compose |

## Notes
- **Credit seeding for dev**: Add internal `POST /api/audio/admin/credits/topup` (no Gateway route) that grants N credits to userId. Required for smoke testing.
- **CMS Mytone integration** (out of scope now): `POST /set-crbt` should accept `{jobId, version}` and call CMS Mytone API with the chosen audio URL. Return 501 stub.
- **Audio format verification**: After Gemini returns audio bytes, verify it's actually 48kHz stereo with `ffprobe`. Re-encode if not: `ffmpeg -i input.mp3 -ar 48000 -ac 2 -b:a 128k output.mp3`.
- **DIY fade timing**: Fade-out start time depends on actual segment duration. Calculate dynamically: `fadeOutStart = (segmentEndMs - segmentStartMs) / 1000.0 - 0.5`.
