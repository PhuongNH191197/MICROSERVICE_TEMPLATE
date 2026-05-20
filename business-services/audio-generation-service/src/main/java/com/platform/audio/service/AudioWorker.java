package com.platform.audio.service;
import com.platform.audio.client.ElevenLabsClient;
import com.platform.audio.client.GeminiLyriaClient;
import com.platform.audio.dto.response.PreviewVersion;
import com.platform.audio.entity.AudioJob;
import com.platform.audio.enums.JobStatus;
import com.platform.audio.processing.FfmpegProcessor;
import com.platform.audio.repository.AudioJobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component @RequiredArgsConstructor @Slf4j
public class AudioWorker {
    private final AudioJobRepository jobRepo;
    private final GeminiLyriaClient geminiClient;
    private final ElevenLabsClient elevenLabsClient;
    private final FfmpegProcessor ffmpegProcessor;
    private final MinioAudioService minioService;
    private final StringRedisTemplate redisTemplate;
    private final CreditService creditService;
    private final EventPublisher eventPublisher;

    @FunctionalInterface
    private interface JobRunner { void run(AudioJob job) throws Exception; }

    @PostConstruct
    public void recoverStuckJobs() {
        List<AudioJob> stuck = jobRepo.findByStatus(JobStatus.PROCESSING);
        for (AudioJob j : stuck) {
            j.setStatus(JobStatus.PENDING);
            j.setUpdatedAt(Instant.now());
            jobRepo.save(j);
        }
        if (!stuck.isEmpty())
            log.warn("Recovered {} stuck PROCESSING jobs on startup", stuck.size());
    }

    @RabbitListener(queues = "#{T(com.platform.audio.config.RabbitMQConfig).AI_GENERATE_QUEUE}")
    public void onAiJob(String jobId) {
        process(UUID.fromString(jobId), this::runAiJob);
    }

    @RabbitListener(queues = "#{T(com.platform.audio.config.RabbitMQConfig).DIY_MIX_QUEUE}")
    public void onDiyJob(String jobId) {
        process(UUID.fromString(jobId), this::runDiyJob);
    }

    private void process(UUID jobId, JobRunner runner) {
        AudioJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null) { log.warn("Job {} not found, skipping", jobId); return; }

        job.setStatus(JobStatus.PROCESSING);
        job.setUpdatedAt(Instant.now());
        jobRepo.save(job);

        try {
            runner.run(job);
        } catch (Exception e) {
            log.error("Job {} failed attempt={}: {}", jobId, job.getRetryCount() + 1, e.getMessage(), e);
            job.setRetryCount(job.getRetryCount() + 1);
            if (job.getRetryCount() >= 3) {
                job.setStatus(JobStatus.FAILED);
                job.setErrorMessage("Max retries exceeded: " + e.getMessage());
                job.setUpdatedAt(Instant.now());
                jobRepo.save(job);
                eventPublisher.publishFailed(job);
                try { creditService.refundCredit(job.getUserId(), job.getId()); }
                catch (Exception ex) { log.error("Failed to refund credit for job {}: {}", jobId, ex.getMessage()); }
            } else {
                int[] delays = {5000, 15000, 45000};
                try { Thread.sleep(delays[Math.min(job.getRetryCount() - 1, 2)]); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                job.setStatus(JobStatus.PENDING);
                job.setUpdatedAt(Instant.now());
                jobRepo.save(job);
                log.info("Re-queuing job {} attempt {}", jobId, job.getRetryCount());
            }
        }
    }

    private void runAiJob(AudioJob job) throws Exception {
        byte[] raw = geminiClient.generateClip(job.getPrompt());
        Path rawPath = writeTempFile(raw);
        Path padded = null;
        try {
            padded = ffmpegProcessor.padToMinDuration(rawPath, 40);
            byte[] audio = Files.readAllBytes(padded);
            String objectName = minioService.uploadAudio(audio, job.getUserId(),
                job.getId() + ".mp3", "audio/mpeg");
            String presignedUrl = minioService.generatePresignedGet(objectName);

            if (job.getCacheKey() != null) {
                redisTemplate.opsForValue().set(job.getCacheKey(), presignedUrl, 30, TimeUnit.DAYS);
                log.info("Cached AI result cacheKey={}", job.getCacheKey());
            }

            job.setStatus(JobStatus.COMPLETED);
            job.setAudioUrl("/api/audio/" + job.getId());
            job.setFileSizeBytes((long) audio.length);
            job.setCompletedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            jobRepo.save(job);
            eventPublisher.publishGenerated(job);
            log.info("AI job {} COMPLETED", job.getId());
        } finally {
            ffmpegProcessor.deleteSilently(rawPath);
            ffmpegProcessor.deleteSilently(padded);
        }
    }

    private void runDiyJob(AudioJob job) throws Exception {
        byte[] voiceBytes = elevenLabsClient.synthesize(job.getTtsText(), job.getVoiceId());
        Path voicePath = writeTempFile(voiceBytes);
        Path musicPath = null;
        Path cutPath = null;
        List<Path> mixed = new ArrayList<>();

        try {
            musicPath = minioService.downloadToTemp(job.getSourceFileKey());
            cutPath = ffmpegProcessor.cutSegment(musicPath,
                job.getSegmentStartMs(), job.getSegmentEndMs());
            mixed = ffmpegProcessor.mix3Versions(voicePath, cutPath);

            String[] labels = {"Voice Noi Bat", "Can Bang", "Nhac Noi Bat"};
            List<PreviewVersion> versions = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                byte[] bytes = Files.readAllBytes(mixed.get(i));
                minioService.uploadAudio(bytes, job.getUserId(),
                    job.getId() + "_v" + (i + 1) + ".mp3", "audio/mpeg");
                versions.add(new PreviewVersion("v" + (i + 1),
                    "/api/audio/" + job.getId() + "/v" + (i + 1), labels[i]));
            }

            job.setStatus(JobStatus.COMPLETED);
            job.setPreviewVersions(versions);
            job.setCompletedAt(Instant.now());
            job.setUpdatedAt(Instant.now());
            jobRepo.save(job);
            eventPublisher.publishGenerated(job);
            log.info("DIY job {} COMPLETED with {} versions", job.getId(), versions.size());
        } finally {
            ffmpegProcessor.deleteSilently(voicePath);
            ffmpegProcessor.deleteSilently(musicPath);
            ffmpegProcessor.deleteSilently(cutPath);
            for (Path p : mixed) ffmpegProcessor.deleteSilently(p);
        }
    }

    private Path writeTempFile(byte[] bytes) throws Exception {
        Path p = Files.createTempFile("audio_", ".mp3");
        Files.write(p, bytes);
        return p;
    }
}
