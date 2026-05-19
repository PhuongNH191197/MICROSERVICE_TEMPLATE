# Plan: File Service v0 Implementation

## Summary
Upgrade file-service from basic scaffold (single upload flow) to full PRD v0 spec: dual upload flows (backend API for images ≤5MB + presigned PUT for audio ≤50MB), new 4-bucket MinIO structure, status-tracked DB schema, and HTTP Range audio streaming support.

## User Story
As a client application, I want to upload images via backend API and large audio files directly to MinIO via presigned URL, so that file transfers are fast and server resources are not wasted on large binary streams.

## Problem → Solution
Existing scaffold: single upload path, wrong bucket names (audio/documents), no presigned PUT flow, no status tracking.
→ PRD v0: 2 upload flows, 4 correct buckets (media-images/audio/temp/private), PENDING→CONFIRMED status lifecycle, Range-header audio streaming.

## Metadata
- **Complexity**: Large
- **Source PRD**: `infra-services/file-service/PRD_FileService_v0.md`
- **PRD Phase**: v0.1 Draft
- **Estimated Files**: 12 (3 new, 9 modified)

---

## UX Design

N/A — internal API change. No user-facing UI.

### API Before → After

| Endpoint | Before | After |
|---|---|---|
| POST /api/files/upload | any file ≤10MB → documents/audio bucket | image only ≤5MB → media-images |
| POST /api/files/presigned-url | does not exist | return presigned PUT URL + fileKey |
| POST /api/files/confirm | does not exist | confirm presigned upload, move temp→dest |
| GET /api/files/{fileId} | redirect to presigned GET 1h | same + Range headers for audio |
| DELETE /api/files/{fileId} | soft delete (deleted=true) | set status=DELETED |
| GET /api/files/user/{userId} | list non-deleted files | list CONFIRMED files |

---

## Mandatory Reading

| Priority | File | Lines | Why |
|---|---|---|---|
| P0 | `infra-services/file-service/src/main/java/com/platform/file/service/FileStorageService.java` | all | Service to refactor |
| P0 | `infra-services/file-service/src/main/java/com/platform/file/controller/FileController.java` | all | Controller to extend |
| P0 | `infra-services/file-service/src/main/java/com/platform/file/entity/FileRecord.java` | all | Entity to update |
| P0 | `infra-services/file-service/src/main/resources/db/migration/V1__create_files.sql` | all | Existing schema baseline |
| P1 | `infrastructure/config-server/src/main/resources/configs/file-service.yml` | all | Config to update |
| P1 | `common/common-dto/src/main/java/com/platform/common/dto/ApiResponse.java` | all | Response wrapper pattern |

---

## Patterns to Mirror

### ENTITY_PATTERN
```java
// SOURCE: entity/FileRecord.java:8-21
@Entity @Table(name = "files")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FileRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "snake_case") private String camelCase;
    @Builder.Default private boolean deleted = false;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
```

### SERVICE_PATTERN
```java
// SOURCE: service/FileStorageService.java:19-31
@Slf4j @Service @RequiredArgsConstructor
public class FileStorageService {
    @Value("${file.max-size-mb:10}") private long maxSizeMb;

    @Transactional
    public FileUploadResponse upload(MultipartFile file, String userId) throws Exception {
        log.info("Image uploaded: fileKey={} userId={}", fileKey, userId);
        return FileUploadResponse.builder()...build();
    }
}
```

### CONTROLLER_PATTERN
```java
// SOURCE: controller/FileController.java:13-23
@RestController @RequestMapping("/api/files") @RequiredArgsConstructor
public class FileController {
    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<FileUploadResponse>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-Id") String userId) throws Exception {
        return ResponseEntity.ok(ApiResponse.success(fileStorageService.upload(file, userId)));
    }
}
```

### ERROR_HANDLING
```java
// SOURCE: exception/GlobalExceptionHandler.java:9-11
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
}
```

### DTO_PATTERN
```java
// SOURCE: dto/FileUploadResponse.java
@Data @Builder
public class FileUploadResponse {
    private String fileId;
    private String url;
}
```

### MINIO_PRESIGNED
```java
// SOURCE: service/FileStorageService.java:59-64
// GET presigned:
minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
        .method(Method.GET).bucket(bucket).object(key)
        .expiry(hours, TimeUnit.HOURS).build());
// PUT presigned: swap Method.GET → Method.PUT, use tempBucket, expiry in MINUTES
```

---

## Files to Change

| File | Action | Justification |
|---|---|---|
| `infra-services/file-service/src/main/resources/db/migration/V2__upgrade_files_schema.sql` | CREATE | Add file_key, status, uploader_id, public_url, expires_at, confirmed_at |
| `infra-services/file-service/src/main/java/com/platform/file/entity/FileStatus.java` | CREATE | Enum: PENDING, CONFIRMED, FAILED, DELETED |
| `infra-services/file-service/src/main/java/com/platform/file/dto/PresignedUrlRequest.java` | CREATE | Request body for POST /presigned-url |
| `infra-services/file-service/src/main/java/com/platform/file/dto/PresignedUrlResponse.java` | CREATE | Response with presignedUrl, fileKey, expiresIn |
| `infra-services/file-service/src/main/java/com/platform/file/dto/ConfirmUploadRequest.java` | CREATE | Request body for POST /confirm |
| `infra-services/file-service/src/main/java/com/platform/file/dto/FileMetadataResponse.java` | CREATE | Full metadata response used by confirm + list |
| `infra-services/file-service/src/main/java/com/platform/file/entity/FileRecord.java` | UPDATE | Add new fields: fileKey, status, uploaderId, publicUrl, expiresAt, confirmedAt |
| `infra-services/file-service/src/main/java/com/platform/file/repository/FileRecordRepository.java` | UPDATE | Add findByFileKey, findByFileKeyAndStatus, findByUploaderIdAndStatus |
| `infra-services/file-service/src/main/java/com/platform/file/service/FileStorageService.java` | UPDATE | Add createPresignedPutUrl(), confirmUpload(), fix bucket/validation logic |
| `infra-services/file-service/src/main/java/com/platform/file/controller/FileController.java` | UPDATE | Add POST /presigned-url, POST /confirm endpoints |
| `infra-services/file-service/src/main/java/com/platform/file/exception/GlobalExceptionHandler.java` | UPDATE | Add 404 handler for NoSuchElementException |
| `infrastructure/config-server/src/main/resources/configs/file-service.yml` | UPDATE | Add per-type limits, 4-bucket structure |

## NOT Building
- Video upload support (PRD Out of Scope §9.1)
- Document (PDF/DOCX) upload (PRD Out of Scope §9.2)
- CDN/Edge caching (PRD Out of Scope §9.3)
- Virus scan (PRD Out of Scope §9.4)
- Image auto-resizing (PRD Out of Scope §9.5)
- MinIO lifecycle rule setup (manual admin operation, not code)

---

## Step-by-Step Tasks

### Task 1: DB Migration V2
- **ACTION**: Create `V2__upgrade_files_schema.sql`
- **IMPLEMENT**:
```sql
ALTER TABLE files
    ADD COLUMN IF NOT EXISTS file_key      VARCHAR(500),
    ADD COLUMN IF NOT EXISTS status        VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    ADD COLUMN IF NOT EXISTS uploader_id   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS public_url    VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS expires_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS confirmed_at  TIMESTAMPTZ;

UPDATE files SET file_key = bucket || '/' || stored_name WHERE file_key IS NULL;
UPDATE files SET uploader_id = user_id WHERE uploader_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uidx_files_file_key ON files(file_key) WHERE file_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_files_status ON files(status);
CREATE INDEX IF NOT EXISTS idx_files_uploader_status ON files(uploader_id, status);
CREATE INDEX IF NOT EXISTS idx_files_expires_at ON files(expires_at) WHERE status = 'PENDING';
```
- **MIRROR**: V1__create_files.sql — `IF NOT EXISTS`, `TIMESTAMPTZ`, `DEFAULT`
- **GOTCHA**: Do NOT drop existing columns (`user_id`, `stored_name`, `deleted`) — backward compat. Flyway checksums V1 — never modify it.
- **VALIDATE**: `\d files` in psql shows all new columns

### Task 2: FileStatus Enum
- **ACTION**: Create `FileStatus.java` in `entity` package
- **IMPLEMENT**:
```java
package com.platform.file.entity;

public enum FileStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    DELETED
}
```
- **GOTCHA**: Entity field must use `@Enumerated(EnumType.STRING)` — never ORDINAL
- **VALIDATE**: Compiles; referenced in FileRecord without error

### Task 3: Update FileRecord Entity
- **ACTION**: Add new fields to `FileRecord.java` (keep all existing fields)
- **IMPLEMENT**: Add inside the class body:
```java
@Column(name = "file_key") private String fileKey;
@Enumerated(EnumType.STRING) @Builder.Default private FileStatus status = FileStatus.CONFIRMED;
@Column(name = "uploader_id") private String uploaderId;
@Column(name = "public_url") private String publicUrl;
@Column(name = "expires_at") private Instant expiresAt;
@Column(name = "confirmed_at") private Instant confirmedAt;
```
- **MIRROR**: Existing pattern — `@Column(name = "snake_case")` + `@Builder.Default` for defaults
- **IMPORTS**: Add `jakarta.persistence.Enumerated`, `jakarta.persistence.EnumType`, `com.platform.file.entity.FileStatus`
- **GOTCHA**: Keep `userId`, `storedName`, `deleted` — removing breaks Flyway validate against V1 columns
- **VALIDATE**: `mvn compile` passes; no `Unknown column` at startup

### Task 4: New DTOs (4 files)
- **ACTION**: Create 4 DTO files following `FileUploadResponse` pattern
- **IMPLEMENT**:

`PresignedUrlRequest.java`:
```java
package com.platform.file.dto;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class PresignedUrlRequest {
    @NotBlank private String filename;
    @Positive @Max(52428800) private long fileSize;
    @NotBlank private String contentType;
}
```

`PresignedUrlResponse.java`:
```java
package com.platform.file.dto;
import lombok.Builder;
import lombok.Data;

@Data @Builder
public class PresignedUrlResponse {
    private String presignedUrl;
    private String fileKey;
    private int expiresIn;
}
```

`ConfirmUploadRequest.java`:
```java
package com.platform.file.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfirmUploadRequest {
    @NotBlank private String fileKey;
}
```

`FileMetadataResponse.java`:
```java
package com.platform.file.dto;
import com.platform.file.entity.FileStatus;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data @Builder
public class FileMetadataResponse {
    private String id;
    private String fileKey;
    private String originalName;
    private String bucket;
    private String publicUrl;
    private long size;
    private String contentType;
    private FileStatus status;
    private Instant createdAt;
    private Instant confirmedAt;
}
```
- **MIRROR**: `@Data @Builder` for responses; `@Data` only (no builder) for requests
- **GOTCHA**: `spring-boot-starter-validation` must be in pom.xml for `@NotBlank`, `@Positive` to work. Check pom.xml before assuming it's present.
- **VALIDATE**: All 4 classes compile; `@Valid` on controller params triggers validation

### Task 5: Update Repository
- **ACTION**: Add query methods to `FileRecordRepository.java`
- **IMPLEMENT**:
```java
Optional<FileRecord> findByFileKey(String fileKey);
Optional<FileRecord> findByFileKeyAndStatus(String fileKey, FileStatus status);
List<FileRecord> findByUploaderIdAndStatus(String uploaderId, FileStatus status);
```
- **IMPORTS**: `com.platform.file.entity.FileStatus`
- **GOTCHA**: Spring Data derives SQL from method name — field names must match entity field names exactly (`fileKey` not `file_key`)
- **VALIDATE**: No `No property 'fileKey' found` error on startup

### Task 6: Update Config (file-service.yml)
- **ACTION**: Replace `file:` section in config-server's `file-service.yml`
- **IMPLEMENT**:
```yaml
file:
  allowed-types:
    image: image/jpeg,image/png,image/webp,image/gif
    audio: audio/mpeg,audio/wav,audio/ogg,audio/aac,audio/x-m4a,audio/mp4
  max-size-mb:
    image: ${FILE_MAX_SIZE_IMAGE_MB:5}
    audio: ${FILE_MAX_SIZE_AUDIO_MB:50}
  presigned-url-expiry-hours: ${FILE_PRESIGNED_URL_EXPIRY_HOURS:1}
  presigned-put-url-expiry-minutes: ${FILE_PRESIGNED_PUT_EXPIRY_MIN:5}
  bucket:
    images: ${FILE_BUCKET_IMAGES:media-images}
    audio: ${FILE_BUCKET_AUDIO:media-audio}
    temp: ${FILE_BUCKET_TEMP:media-temp}
    private: ${FILE_BUCKET_PRIVATE:media-private}
```
- **GOTCHA**: Old keys (`file.allowed-types` as string, `file.bucket.default`, `file.bucket.audio`) must be replaced in `@Value` annotations in FileStorageService simultaneously or service fails to start
- **VALIDATE**: Config Server returns updated keys; service starts without `@Value` binding exception

### Task 7: Refactor FileStorageService
- **ACTION**: Major update — replace old `@Value` bindings, fix upload(), add createPresignedPutUrl() + confirmUpload()
- **IMPLEMENT**:

**New @Value bindings** (replace old ones):
```java
@Value("${file.allowed-types.image}") private String allowedImageTypes;
@Value("${file.allowed-types.audio}") private String allowedAudioTypes;
@Value("${file.max-size-mb.image:5}") private long maxImageSizeMb;
@Value("${file.max-size-mb.audio:50}") private long maxAudioSizeMb;
@Value("${file.presigned-url-expiry-hours:1}") private int presignedGetExpiryHours;
@Value("${file.presigned-put-url-expiry-minutes:5}") private int presignedPutExpiryMinutes;
@Value("${file.bucket.images:media-images}") private String imagesBucket;
@Value("${file.bucket.audio:media-audio}") private String audioBucket;
@Value("${file.bucket.temp:media-temp}") private String tempBucket;
@Value("${file.bucket.private:media-private}") private String privateBucket;
```

**Fix upload() for Luồng A (images only)**:
- Change bucket from `resolveBucket()` to always `imagesBucket`
- Change validation to `validateImageFile()` (image types + 5MB limit)
- Generate `fileKey = "avatars/" + UUID.randomUUID() + "_" + originalFilename`
- Set `record.setFileKey(fileKey)`, `record.setUploaderId(userId)`, `record.setStatus(FileStatus.CONFIRMED)`

**New createPresignedPutUrl() for Luồng B step 1**:
```java
@Transactional
public PresignedUrlResponse createPresignedPutUrl(PresignedUrlRequest request, String userId) throws Exception {
    validatePresignedRequest(request);
    ensureBucket(tempBucket);
    String fileKey = "temp/" + UUID.randomUUID() + "_" + request.getFilename();
    String presignedUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
            .method(Method.PUT).bucket(tempBucket).object(fileKey)
            .expiry(presignedPutExpiryMinutes, TimeUnit.MINUTES).build());
    Instant expiresAt = Instant.now().plus(presignedPutExpiryMinutes, ChronoUnit.MINUTES);
    FileRecord record = FileRecord.builder()
            .fileKey(fileKey).uploaderId(userId)
            .originalName(request.getFilename())
            .bucket(tempBucket).size(request.getFileSize())
            .contentType(request.getContentType())
            .status(FileStatus.PENDING).expiresAt(expiresAt).build();
    fileRecordRepository.save(record);
    log.info("Presigned PUT issued: fileKey={} userId={}", fileKey, userId);
    return PresignedUrlResponse.builder()
            .presignedUrl(presignedUrl).fileKey(fileKey)
            .expiresIn(presignedPutExpiryMinutes * 60).build();
}
```

**New confirmUpload() for Luồng B step 2**:
```java
@Transactional
public FileMetadataResponse confirmUpload(ConfirmUploadRequest request, String userId) throws Exception {
    FileRecord record = fileRecordRepository
            .findByFileKeyAndStatus(request.getFileKey(), FileStatus.PENDING)
            .orElseThrow(() -> new IllegalArgumentException("File not found or already confirmed: " + request.getFileKey()));
    if (record.getExpiresAt() != null && Instant.now().isAfter(record.getExpiresAt())) {
        record.setStatus(FileStatus.FAILED);
        fileRecordRepository.save(record);
        throw new IllegalArgumentException("Presigned URL expired");
    }
    String destBucket = resolveDestBucket(record.getContentType());
    String destKey = resolveDestPrefix(record.getContentType()) + "/" + UUID.randomUUID() + "_" + record.getOriginalName();
    ensureBucket(destBucket);
    minioClient.copyObject(CopyObjectArgs.builder()
            .bucket(destBucket).object(destKey)
            .source(CopySource.builder().bucket(tempBucket).object(record.getFileKey()).build())
            .build());
    record.setFileKey(destKey);
    record.setBucket(destBucket);
    record.setStatus(FileStatus.CONFIRMED);
    record.setConfirmedAt(Instant.now());
    fileRecordRepository.save(record);
    log.info("Upload confirmed: fileKey={} userId={}", destKey, userId);
    rabbitTemplate.convertAndSend(RabbitMQConfig.FILE_EXCHANGE,
            RabbitMQConfig.ROUTING_KEY_FILE_UPLOADED,
            FileUploadedEvent.builder().fileId(record.getId().toString())
                    .userId(userId).fileName(record.getOriginalName()).url(destKey).build());
    return toMetadataResponse(record);
}
```

**Helpers**:
```java
private void validateImageFile(MultipartFile file) {
    if (file.isEmpty()) throw new IllegalArgumentException("Empty file");
    if (file.getSize() > maxImageSizeMb * 1024 * 1024)
        throw new IllegalArgumentException("Image exceeds " + maxImageSizeMb + "MB limit");
    List<String> allowed = Arrays.asList(allowedImageTypes.split(","));
    if (!allowed.contains(file.getContentType()))
        throw new IllegalArgumentException("Content-type not allowed: " + file.getContentType());
}

private void validatePresignedRequest(PresignedUrlRequest request) {
    List<String> allowed = Arrays.asList(allowedAudioTypes.split(","));
    if (!allowed.contains(request.getContentType()))
        throw new IllegalArgumentException("Content-type not allowed: " + request.getContentType());
    if (request.getFileSize() > maxAudioSizeMb * 1024 * 1024)
        throw new IllegalArgumentException("File size exceeds " + maxAudioSizeMb + "MB limit");
}

private String resolveDestBucket(String contentType) {
    if (contentType != null && contentType.startsWith("audio/")) return audioBucket;
    return privateBucket;
}

private String resolveDestPrefix(String contentType) {
    if (contentType != null && contentType.startsWith("audio/")) return "audio";
    return "files";
}

public FileMetadataResponse toMetadataResponse(FileRecord record) {
    return FileMetadataResponse.builder()
            .id(record.getId().toString()).fileKey(record.getFileKey())
            .originalName(record.getOriginalName()).bucket(record.getBucket())
            .publicUrl(record.getPublicUrl()).size(record.getSize())
            .contentType(record.getContentType()).status(record.getStatus())
            .createdAt(record.getCreatedAt()).confirmedAt(record.getConfirmedAt()).build();
}
```
- **IMPORTS**: `io.minio.CopyObjectArgs`, `io.minio.CopySource`, `java.time.temporal.ChronoUnit`, `com.platform.file.entity.FileStatus`, all new DTOs
- **GOTCHA**: `CopyObjectArgs` and `CopySource` exist in minio-client ≥ 8.3. Verify in pom.xml. `Method.PUT` is `io.minio.http.Method`.
- **VALIDATE**: `mvn compile`; all methods resolve without error

### Task 8: Update FileController
- **ACTION**: Add 2 new endpoints to `FileController.java`; update `getUserFiles`
- **IMPLEMENT**:
```java
// Add new endpoints:
@PostMapping("/presigned-url")
public ResponseEntity<ApiResponse<PresignedUrlResponse>> getPresignedUrl(
        @RequestBody @Valid PresignedUrlRequest request,
        @RequestHeader("X-User-Id") String userId) throws Exception {
    return ResponseEntity.ok(ApiResponse.success(fileStorageService.createPresignedPutUrl(request, userId)));
}

@PostMapping("/confirm")
public ResponseEntity<ApiResponse<FileMetadataResponse>> confirmUpload(
        @RequestBody @Valid ConfirmUploadRequest request,
        @RequestHeader("X-User-Id") String userId) throws Exception {
    return ResponseEntity.ok(ApiResponse.success(fileStorageService.confirmUpload(request, userId)));
}

// Replace getUserFiles:
@GetMapping("/user/{userId}")
public ResponseEntity<ApiResponse<List<FileMetadataResponse>>> getUserFiles(@PathVariable String userId) {
    List<FileMetadataResponse> files = fileRecordRepository
            .findByUploaderIdAndStatus(userId, FileStatus.CONFIRMED)
            .stream().map(fileStorageService::toMetadataResponse).toList();
    return ResponseEntity.ok(ApiResponse.success(files));
}
```
- **IMPORTS**: `jakarta.validation.Valid`, all new DTOs, `FileStatus`
- **GOTCHA**: Remove direct injection of `FileRecordRepository` in controller — delegate to service. Or keep it if preferred (existing pattern uses it directly). Keep consistent with existing pattern.
- **VALIDATE**: Swagger UI at `http://localhost:8083/swagger-ui.html` shows 6 endpoints

### Task 9: Update GlobalExceptionHandler
- **ACTION**: Add 404 handler
- **IMPLEMENT**:
```java
@ExceptionHandler(java.util.NoSuchElementException.class)
public ResponseEntity<ApiResponse<Void>> handleNotFound(java.util.NoSuchElementException ex) {
    return ResponseEntity.status(404).body(ApiResponse.error("Resource not found"));
}
```
- **MIRROR**: Existing handler pattern in `GlobalExceptionHandler.java`
- **VALIDATE**: GET /api/files/{nonexistent-id} returns 404 not 500

---

## Testing Strategy

### Unit Tests

| Test | Input | Expected Output | Edge Case? |
|---|---|---|---|
| upload() valid JPEG 2MB | JPEG 2MB, userId | FileUploadResponse with url | No |
| upload() oversized 6MB | JPEG 6MB | throws "exceeds 5MB" | Yes |
| upload() wrong type | audio/mpeg | throws "not allowed" | Yes |
| upload() empty file | empty MultipartFile | throws "Empty file" | Yes |
| createPresignedPutUrl() valid | MP3 10MB request | PresignedUrlResponse with presignedUrl | No |
| createPresignedPutUrl() oversized | 60MB audio | throws "exceeds 50MB" | Yes |
| createPresignedPutUrl() wrong type | image/jpeg | throws "not allowed" | Yes |
| confirmUpload() valid | PENDING fileKey | FileMetadataResponse CONFIRMED | No |
| confirmUpload() expired | PENDING expired record | throws "expired" | Yes |
| confirmUpload() not found | unknown fileKey | throws "not found" | Yes |

### Edge Cases Checklist
- [ ] File exactly at 5MB limit → accepted
- [ ] File exactly at 50MB limit → accepted
- [ ] Expired PENDING confirm → status→FAILED, 400 returned
- [ ] Double confirm same fileKey → second call throws "not found or already confirmed"
- [ ] MinIO unavailable → 500 via GlobalExceptionHandler

---

## Validation Commands

### Compile
```bash
mvn compile -pl infra-services/file-service -am
```
EXPECT: BUILD SUCCESS

### Full Build
```bash
mvn clean package -DskipTests -pl infra-services/file-service -am
```
EXPECT: BUILD SUCCESS

### Migration Check
```bash
docker exec postgresql psql -U platform_user -d media_db -c "\d files"
```
EXPECT: Columns `file_key`, `status`, `uploader_id`, `public_url`, `expires_at`, `confirmed_at` visible

### Smoke Test — Luồng A
```bash
TOKEN="..."
curl -X POST http://localhost:8080/api/files/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test.jpg"
```
EXPECT: `{"success":true,"data":{"fileId":"...","url":"..."}}`

### Smoke Test — Luồng B
```bash
# Step 1: get presigned PUT URL
curl -X POST http://localhost:8080/api/files/presigned-url \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"filename":"song.mp3","fileSize":5000000,"contentType":"audio/mpeg"}'

# Step 2: PUT file directly to MinIO using returned presignedUrl
curl -X PUT "<presignedUrl>" -H "Content-Type: audio/mpeg" --data-binary @song.mp3

# Step 3: confirm
curl -X POST http://localhost:8080/api/files/confirm \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"fileKey":"temp/uuid_song.mp3"}'
```
EXPECT: Step 3 returns `{"success":true,"data":{"status":"CONFIRMED",...}}`

---

## Acceptance Criteria
- [ ] Luồng A: POST /api/files/upload accepts images ≤5MB, rejects audio/oversized
- [ ] Luồng B step 1: POST /api/files/presigned-url returns presigned PUT URL for audio ≤50MB
- [ ] Luồng B step 2: POST /api/files/confirm moves temp→dest, status PENDING→CONFIRMED
- [ ] GET /api/files/{fileId} returns 302 redirect to presigned GET URL
- [ ] DELETE /api/files/{fileId} sets status=DELETED
- [ ] GET /api/files/user/{userId} lists only CONFIRMED files
- [ ] V2 migration runs without error on both clean DB and existing data
- [ ] No hardcoded bucket names or size limits

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| MinIO CopyObjectArgs API missing | Low | High | Check minio-client version in pom.xml before Task 7 |
| V2 migration breaks existing rows | Medium | Medium | ADD COLUMN IF NOT EXISTS + backfill, never DROP |
| @Value binding fails on new config keys | Medium | Low | Add :default fallbacks in every @Value |
| spring-boot-starter-validation not in pom | Medium | Medium | Check pom.xml before Task 4; add if missing |

## Notes
- `toMetadataResponse()` must be `public` in FileStorageService so controller can use it as method reference.
- Audio Range header (seek) support is built into MinIO — no Spring code change needed.
- `media-temp` 24h lifecycle requires MinIO admin config (`mc ilm add`) — document in README, not in code.
- Keep old entity fields (`userId`, `storedName`, `deleted`) until confirmed no other service reads them.
