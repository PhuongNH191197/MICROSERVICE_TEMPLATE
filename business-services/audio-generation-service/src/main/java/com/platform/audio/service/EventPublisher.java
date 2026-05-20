package com.platform.audio.service;
import com.platform.audio.config.RabbitMQConfig;
import com.platform.audio.entity.AudioJob;
import com.platform.common.events.AudioFailedEvent;
import com.platform.common.events.AudioGeneratedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor @Slf4j
public class EventPublisher {
    private final RabbitTemplate rabbitTemplate;

    public void publishGenerated(AudioJob job) {
        List<String> previewUrls = job.getPreviewVersions() != null
            ? job.getPreviewVersions().stream().map(v -> v.getUrl()).collect(Collectors.toList())
            : (job.getAudioUrl() != null ? List.of(job.getAudioUrl()) : List.of());

        AudioGeneratedEvent event = AudioGeneratedEvent.builder()
            .jobId(job.getId().toString())
            .userId(job.getUserId())
            .jobType(job.getJobType().name())
            .title(job.getTitle())
            .audioUrl(job.getAudioUrl())
            .previewUrls(previewUrls)
            .durationSeconds(job.getDurationSeconds())
            .build();
        rabbitTemplate.convertAndSend(RabbitMQConfig.AUDIO_EXCHANGE, "audio.generated", event);
        log.info("Published AudioGeneratedEvent jobId={}", job.getId());
    }

    public void publishFailed(AudioJob job) {
        AudioFailedEvent event = AudioFailedEvent.builder()
            .jobId(job.getId().toString())
            .userId(job.getUserId())
            .errorMessage(job.getErrorMessage())
            .retryCount(job.getRetryCount())
            .build();
        rabbitTemplate.convertAndSend(RabbitMQConfig.AUDIO_EXCHANGE, "audio.failed", event);
        log.info("Published AudioFailedEvent jobId={}", job.getId());
    }
}
