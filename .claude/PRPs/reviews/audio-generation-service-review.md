# Code Review: audio-generation-service

**Reviewed**: 2026-05-20
**Branch**: main (commits de6a153, 57f2e3a)
**Decision**: REQUEST CHANGES

## Summary

Implementation is solid overall — two-flow architecture is correct, credit pessimistic locking is properly implemented, FFmpeg ProcessBuilder usage avoids shell injection, and test coverage is good. Three issues require fixes before production: API key leakage via URL, broken retry re-queue logic, and unprotected admin credit endpoint.

---

## Findings

### HIGH

**H1 — API key exposed in URL query parameter**
`client/GeminiLyriaClient.java:23`
```java
String url = baseUrl + "/v1beta/models/" + LYRIA_MODEL + ":generateContent?key=" + apiKey;
```
The `?key=` query param appears in application logs (line 34 logs the call), proxy access logs, and any network trace. API keys in URLs are a known credential leak vector (OWASP A02).

Fix — use `X-Goog-Api-Key` header instead:
```java
headers.set("X-Goog-Api-Key", apiKey);
// URL without ?key= param
String url = baseUrl + "/v1beta/models/" + LYRIA_MODEL + ":generateContent";
```

---

**H2 — Retry broken: job reset to PENDING but never re-published to RabbitMQ**
`service/AudioWorker.java:77-84`
```java
} else {
    Thread.sleep(delays[...]);
    job.setStatus(JobStatus.PENDING);
    jobRepo.save(job);
    log.info("Re-queuing job {} ...");  // misleading — no publish happens
}
```
`AudioWorker` has no `RabbitTemplate` field. The original RabbitMQ message was already ACK'd (listener container sets `defaultRequeueRejected=false`). After a non-final failure, the job is saved as PENDING but no new message is enqueued. Job is permanently stuck PENDING; `@PostConstruct recoverStuckJobs()` only resets PROCESSING, not PENDING, so it won't recover this on restart.

Fix — inject `RabbitTemplate` and publish after sleep:
```java
// Add to AudioWorker fields:
private final RabbitTemplate rabbitTemplate;

// In the retry else-branch after jobRepo.save(job):
String routingKey = job.getJobType() == JobType.AI_GENERATE
    ? RabbitMQConfig.ROUTING_AI : RabbitMQConfig.ROUTING_DIY;
rabbitTemplate.convertAndSend(RabbitMQConfig.AUDIO_EXCHANGE, routingKey, jobId.toString());
```

---

**H3 — Admin credit topup has no authorization check**
`controller/AudioJobController.java:97-103`
```java
@PostMapping("/admin/credits/topup")
public ResponseEntity<ApiResponse<CreditBalanceResponse>> topup(
        @RequestHeader("X-User-Id") String userId,
        @RequestParam(defaultValue = "10") int amount) {
    creditService.topup(userId, amount);  // any authenticated user can call
```
No role check. Any user who reaches this endpoint can top up any account's credits by controlling the `X-User-Id` header (if they bypass the gateway). Defense in depth requires authorization at the service layer.

Fix:
```java
@PostMapping("/admin/credits/topup")
@PreAuthorize("hasRole('ADMIN')")  // requires Spring Security with roles
```
Or reject if caller's userId does not match an admin list from config.

---

### MEDIUM

**M1 — Thread.sleep inside @RabbitListener blocks consumer thread**
`service/AudioWorker.java:79`
```java
try { Thread.sleep(delays[Math.min(job.getRetryCount() - 1, 2)]); }
```
With a single-consumer container (default), sleeping 45 seconds on the 3rd retry blocks all other queue messages for that duration. During a full 3-retry cycle: 5+15+45=65 seconds of queue stall.

Fix — use Spring AMQP `RetryOperationsInterceptor` with exponential backoff, or publish retry messages to a delay exchange (TTL-based DLQ), freeing the consumer thread.

---

**M2 — Credit deducted before RabbitMQ publish; no refund if publish fails**
`service/AiGenerateService.java:31-56`
```java
creditService.deductCredit(userId, null);   // line 31
// ... save job ...
rabbitTemplate.convertAndSend(...);          // line 56 — can throw
```
`@Transactional` covers DB, not RabbitMQ. If `convertAndSend` throws (broker down, connection reset), credit is gone and job is PENDING with no message in the queue. Without H2's fix, this also means the job sits PENDING forever.

Fix — wrap publish in try-catch and refund on failure:
```java
try {
    rabbitTemplate.convertAndSend(RabbitMQConfig.AUDIO_EXCHANGE, RabbitMQConfig.ROUTING_AI, job.getId().toString());
} catch (Exception e) {
    creditService.refundCredit(userId, job.getId());
    throw new RuntimeException("Failed to queue job: " + e.getMessage(), e);
}
```

---

**M3 — User-supplied fileKey not validated for path traversal**
`service/MinioAudioService.java:43`
```java
GetObjectArgs.builder().bucket(bucket).object(fileKey).build()
```
`fileKey` comes from `DiyGenerateRequest.sourceFileKey` (user input). A value like `../../other-bucket/admin.mp3` is sent to MinIO verbatim. MinIO behavior with `..` in object names is implementation-specific.

Fix — validate at the request boundary (`DiyGenerateRequest`):
```java
if (sourceFileKey.contains("..") || sourceFileKey.startsWith("/")) {
    throw new IllegalArgumentException("Invalid file key");
}
```
Or use `@Pattern(regexp = "^[\\w\\-/]+\\.\\w{2,5}$")` on the field.

---

**M4 — Missing composite index (user_id, status) for active-job count query**
`db/migration/V1__create_audio_jobs.sql`
`DiyService:45` calls `countByUserIdAndStatusIn(userId, [PENDING, PROCESSING])` on every DIY submit. Two separate indexes exist but no composite. PostgreSQL must intersect two index scans.

Fix — add to a new migration `V4__add_composite_index.sql`:
```sql
CREATE INDEX idx_audio_jobs_user_status ON audio_jobs(user_id, status);
```

---

**M5 — tts_text VARCHAR(100) too short; no input validation**
`db/migration/V1__create_audio_jobs.sql:13`
```sql
tts_text VARCHAR(100),
```
No `@Size` annotation on `DiyGenerateRequest.text`. A user submitting 300-char TTS text gets a DB error at persistence time, not at validation time. The 80-char truncation in `DiyService` applies only to the `title` field, not to `ttsText` itself.

Fix — add `@Size(max = 500)` to `DiyGenerateRequest.text` and increase column to `VARCHAR(500)`, or enforce `@Size(max = 100)` if 100 is intentional.

---

### LOW

**L1 — Presigned URL cached in Redis; expires in 1h but TTL is 30 days**
`service/AudioWorker.java:101`
```java
redisTemplate.opsForValue().set(job.getCacheKey(), presignedUrl, 30, TimeUnit.DAYS);
```
Presigned URL expires in 1 hour (`presigned-url-expiry-hours:1`). Cache HIT after 1 hour returns an expired URL, causing a 403 from MinIO.

Fix — cache the MinIO object name instead; generate a fresh presigned URL on each cache HIT in `AiGenerateService.submit()`.

---

**L2 — recoverStuckJobs not @Transactional**
`service/AudioWorker.java:35-44`
Iterates and saves multiple jobs without a transaction. Crash mid-loop leaves some jobs PROCESSING, others PENDING.

Fix — add `@Transactional` to `recoverStuckJobs()`.

---

**L3 — refundCredit silently creates ghost credit account**
`service/CreditService.java:36`
```java
.orElseGet(() -> UserCredit.builder().userId(userId).balance(0).build())
```
If userId has no credit row (deleted user), refund creates a new account with balance 1. A refund for a non-existent user should throw, not create.

Fix:
```java
.orElseThrow(() -> new IllegalStateException("No credit account to refund for: " + userId))
```

---

## Validation Results

| Check | Result |
|---|---|
| Compilation | Pass |
| Unit Tests (50 total) | Pass — 0 failures |
| Command injection (FFmpeg) | Pass — ProcessBuilder array form, no shell |
| userId always from X-User-Id header | Pass — all 4 controllers correct |
| API key handling | Fail — H1 (Gemini key in URL) |
| DB indexes | Partial — M4 missing composite index |

---

## Files Reviewed

| File | Finding |
|---|---|
| `client/GeminiLyriaClient.java` | H1 |
| `service/AudioWorker.java` | H2, M1, L2 |
| `controller/AudioJobController.java` | H3 |
| `service/AiGenerateService.java` | M2, L1 (cache stores URL) |
| `service/MinioAudioService.java` | M3 |
| `service/CreditService.java` | L3 |
| `db/migration/V1__create_audio_jobs.sql` | M4, M5 |
| `processing/FfmpegProcessor.java` | Clean |
| All other files | Clean |

---

## Priority Fix Order

1. **H2** — Add `RabbitTemplate` to `AudioWorker` + publish on retry
2. **H1** — Move Gemini API key to `X-Goog-Api-Key` header
3. **L1** — Cache objectName not presigned URL (fixes L1 and removes H1 log leak from cached calls)
4. **H3** — Add admin role check on topup endpoint
5. **M2** — Refund credit if queue publish fails
6. **M3** — Validate fileKey for path traversal
7. **M4** — Add `V4__add_composite_index.sql` migration
8. **M5** — Increase tts_text column + add @Size on DTO
