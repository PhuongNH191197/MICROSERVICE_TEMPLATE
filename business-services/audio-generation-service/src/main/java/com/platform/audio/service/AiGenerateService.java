package com.platform.audio.service;
import com.platform.audio.config.RabbitMQConfig;
import com.platform.audio.dto.request.AiGenerateRequest;
import com.platform.audio.dto.response.AudioJobResponse;
import com.platform.audio.entity.AudioJob;
import com.platform.audio.enums.JobStatus;
import com.platform.audio.enums.JobType;
import com.platform.audio.repository.AudioJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service @RequiredArgsConstructor @Slf4j
public class AiGenerateService {
    private final AudioJobRepository jobRepo;
    private final CreditService creditService;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public AudioJobResponse submit(AiGenerateRequest req, String userId) {
        String cacheKey = buildCacheKey(req);

        creditService.deductCredit(userId, null);

        String cachedObjectName = redisTemplate.opsForValue().get(cacheKey);
        if (cachedObjectName != null) {
            log.info("Cache HIT cacheKey={} userId={}", cacheKey, userId);
            AudioJob job = AudioJob.builder()
                .userId(userId).jobType(JobType.AI_GENERATE).status(JobStatus.COMPLETED)
                .genre(req.getGenre().name()).mood(req.getMood().name())
                .instrument(req.getInstrument().name())
                .audioUrl(cachedObjectName).cacheKey(cacheKey)
                .completedAt(Instant.now()).build();
            return toResponse(jobRepo.save(job));
        }

        String prompt = buildPrompt(req);
        String title = req.getTitle() != null ? req.getTitle()
            : req.getGenre().getLabel() + " " + req.getMood().getLabel() + " - " + req.getInstrument().getLabel();

        AudioJob job = AudioJob.builder()
            .userId(userId).jobType(JobType.AI_GENERATE).status(JobStatus.PENDING)
            .genre(req.getGenre().name()).mood(req.getMood().name())
            .instrument(req.getInstrument().name()).mode("CLIP")
            .prompt(prompt).title(title).cacheKey(cacheKey).build();
        job = jobRepo.save(job);

        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.AUDIO_EXCHANGE,
                RabbitMQConfig.ROUTING_AI, job.getId().toString());
        } catch (Exception e) {
            creditService.refundCredit(userId, job.getId());
            throw new RuntimeException("Failed to queue job: " + e.getMessage(), e);
        }
        log.info("Queued AI job id={} userId={}", job.getId(), userId);
        return toResponse(job);
    }

    public AudioJobResponse getJob(String jobId, String userId) {
        AudioJob job = jobRepo.findByIdAndUserId(
            java.util.UUID.fromString(jobId), userId)
            .orElseThrow(() -> new com.platform.audio.exception.AudioJobNotFoundException(jobId));
        return toResponse(job);
    }

    private String buildPrompt(AiGenerateRequest req) {
        return String.format(
            "Generate a 30-second %s instrumental music piece with %s mood, " +
            "featuring %s as the lead instrument. No vocals. 48kHz stereo. Radio quality.",
            req.getGenre().getLabel(), req.getMood().getLabel(), req.getInstrument().getLabel());
    }

    private String buildCacheKey(AiGenerateRequest req) {
        try {
            String raw = req.getGenre() + ":" + req.getMood() + ":" + req.getInstrument();
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return "audio:ai:cache:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "audio:ai:cache:" + req.getGenre() + "_" + req.getMood() + "_" + req.getInstrument();
        }
    }

    public AudioJobResponse toResponse(AudioJob job) {
        return AudioJobResponse.builder()
            .jobId(job.getId().toString())
            .status(job.getStatus())
            .jobType(job.getJobType())
            .mode(job.getMode())
            .format(job.getFormat())
            .prompt(job.getPrompt())
            .title(job.getTitle())
            .genre(job.getGenre())
            .mood(job.getMood())
            .instrument(job.getInstrument())
            .audioUrl(job.getAudioUrl())
            .previewVersions(job.getPreviewVersions())
            .durationSeconds(job.getDurationSeconds())
            .fileSizeBytes(job.getFileSizeBytes())
            .retryCount(job.getRetryCount())
            .errorMessage(job.getErrorMessage())
            .createdAt(job.getCreatedAt())
            .completedAt(job.getCompletedAt())
            .estimatedSeconds(job.getStatus() == JobStatus.PENDING ? 30 : null)
            .build();
    }
}
