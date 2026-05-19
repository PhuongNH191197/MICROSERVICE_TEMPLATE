# Implementation Report: File Service v0

## Summary
Upgraded file-service from basic scaffold to PRD v0 spec. Added dual upload flows (Luồng A: backend API for images ≤5MB; Luồng B: presigned PUT for audio ≤50MB), 4-bucket MinIO structure, PENDING→CONFIRMED status lifecycle, and extended DB schema via Flyway V2 migration.

## Tasks Completed

| # | Task | Status |
|---|---|---|
| 0 | Add spring-boot-starter-validation | ✅ |
| 1 | DB Migration V2 | ✅ |
| 2 | FileStatus Enum | ✅ |
| 3 | Update FileRecord Entity | ✅ |
| 4 | New DTOs (4 files) | ✅ |
| 5 | Update Repository | ✅ |
| 6 | Update Config yml | ✅ |
| 7 | Refactor FileStorageService | ✅ |
| 8 | Update FileController | ✅ |
| 9 | Update GlobalExceptionHandler | ✅ |

## Validation

| Check | Status |
|---|---|
| `mvn compile` | ✅ BUILD SUCCESS |
| `mvn clean package -DskipTests` | ✅ BUILD SUCCESS |
| Unit tests | ⏭ Pending |
| Integration | ⏭ Pending (needs docker stack) |

## Files Changed (13 total)

- `pom.xml` — added spring-boot-starter-validation
- `V2__upgrade_files_schema.sql` — CREATED
- `FileStatus.java` — CREATED (enum)
- `FileRecord.java` — +6 fields
- `PresignedUrlRequest.java` — CREATED
- `PresignedUrlResponse.java` — CREATED
- `ConfirmUploadRequest.java` — CREATED
- `FileMetadataResponse.java` — CREATED
- `FileRecordRepository.java` — +3 query methods
- `FileStorageService.java` — full rewrite
- `FileController.java` — +2 endpoints
- `GlobalExceptionHandler.java` — +NoSuchElementException handler
- `configs/file-service.yml` — 4-bucket structure

## Deviations
- `toMetadataResponse()` public (plan said private) — needed for controller method reference
- `getPresignedUrl()` falls back to `storedName` when `fileKey` null — backward compat
- `delete()` sets both `status=DELETED` and `deleted=true` — backward compat

## Next Steps
- [ ] Write unit tests for FileStorageService
- [ ] `docker-compose up -d` → verify V2 migration
- [ ] Smoke test Luồng A + Luồng B
- [ ] MinIO: configure media-temp 24h lifecycle
