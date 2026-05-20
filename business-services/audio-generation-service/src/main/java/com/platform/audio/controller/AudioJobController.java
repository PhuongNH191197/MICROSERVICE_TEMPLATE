package com.platform.audio.controller;
import com.platform.audio.dto.response.AudioJobResponse;
import com.platform.audio.dto.response.CreditBalanceResponse;
import com.platform.audio.entity.AudioJob;
import com.platform.audio.enums.JobStatus;
import com.platform.audio.exception.AudioJobNotFoundException;
import com.platform.audio.repository.AudioJobRepository;
import com.platform.audio.service.AiGenerateService;
import com.platform.audio.service.CreditService;
import com.platform.audio.service.MinioAudioService;
import com.platform.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/audio")
@RequiredArgsConstructor @Slf4j
public class AudioJobController {
    private final AudioJobRepository jobRepo;
    private final AiGenerateService aiGenerateService;
    private final CreditService creditService;
    private final MinioAudioService minioService;

    @Value("${admin.secret:}") private String adminSecret;

    @GetMapping("/jobs")
    public ResponseEntity<ApiResponse<Page<AudioJobResponse>>> listJobs(
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<AudioJobResponse> results = jobRepo
            .findByUserIdAndDeletedFalseOrderByCreatedAtDesc(userId, pageable)
            .map(aiGenerateService::toResponse);
        return ResponseEntity.ok(ApiResponse.success(results));
    }

    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<ApiResponse<Void>> cancelOrDelete(
            @PathVariable String jobId,
            @RequestHeader("X-User-Id") String userId) {
        AudioJob job = jobRepo.findByIdAndUserId(UUID.fromString(jobId), userId)
            .orElseThrow(() -> new AudioJobNotFoundException(jobId));

        if (job.getStatus() == JobStatus.PENDING) {
            job.setStatus(JobStatus.CANCELLED);
            job.setUpdatedAt(Instant.now());
            jobRepo.save(job);
            try { creditService.refundCredit(userId, job.getId()); }
            catch (Exception e) { log.warn("Could not refund credit for job {}: {}", jobId, e.getMessage()); }
            log.info("Cancelled job {} for userId={}", jobId, userId);
        } else if (job.getStatus() == JobStatus.COMPLETED || job.getStatus() == JobStatus.FAILED) {
            job.setDeleted(true);
            job.setUpdatedAt(Instant.now());
            jobRepo.save(job);
        } else {
            return ResponseEntity.status(409)
                .body(ApiResponse.error("Cannot cancel job in status: " + job.getStatus()));
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<?> getAudio(
            @PathVariable String jobId,
            @RequestHeader("X-User-Id") String userId) throws Exception {
        AudioJob job = jobRepo.findByIdAndUserId(UUID.fromString(jobId), userId)
            .orElseThrow(() -> new AudioJobNotFoundException(jobId));
        if (job.getStatus() != JobStatus.COMPLETED || job.getAudioUrl() == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("Audio not ready"));
        }
        String objectName = job.getAudioUrl().startsWith("/api/")
            ? job.getUserId() + "/" + job.getId() + ".mp3"
            : job.getAudioUrl();
        String presigned = minioService.generatePresignedGet(objectName);
        return ResponseEntity.status(302).location(URI.create(presigned)).build();
    }

    @GetMapping("/{jobId}/{version}")
    public ResponseEntity<?> getDiyVersion(
            @PathVariable String jobId,
            @PathVariable String version,
            @RequestHeader("X-User-Id") String userId) throws Exception {
        AudioJob job = jobRepo.findByIdAndUserId(UUID.fromString(jobId), userId)
            .orElseThrow(() -> new AudioJobNotFoundException(jobId));
        if (job.getStatus() != JobStatus.COMPLETED || job.getPreviewVersions() == null) {
            return ResponseEntity.status(404).body(ApiResponse.error("Audio not ready"));
        }
        String objectName = job.getUserId() + "/" + job.getId() + "_" + version + ".mp3";
        String presigned = minioService.generatePresignedGet(objectName);
        return ResponseEntity.status(302).location(URI.create(presigned)).build();
    }

    @PostMapping("/admin/credits/topup")
    public ResponseEntity<ApiResponse<CreditBalanceResponse>> topup(
            @RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-Admin-Secret", required = false) String secret,
            @RequestParam(defaultValue = "10") int amount) {
        if (!adminSecret.isBlank() && !adminSecret.equals(secret)) {
            return ResponseEntity.status(403).body(ApiResponse.error("Forbidden"));
        }
        creditService.topup(userId, amount);
        return ResponseEntity.ok(ApiResponse.success(
            CreditBalanceResponse.builder().userId(userId).balance(creditService.getBalance(userId)).build()));
    }

    @GetMapping("/admin/credits/balance")
    public ResponseEntity<ApiResponse<CreditBalanceResponse>> balance(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ApiResponse.success(
            CreditBalanceResponse.builder().userId(userId).balance(creditService.getBalance(userId)).build()));
    }
}
