package com.platform.audio.entity;
import com.platform.audio.converter.PreviewVersionsConverter;
import com.platform.audio.dto.response.PreviewVersion;
import com.platform.audio.enums.JobStatus;
import com.platform.audio.enums.JobType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity @Table(name = "audio_jobs")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AudioJob {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private String genre;
    private String mood;
    private String instrument;
    private String mode;

    @Column(name = "source_file_key")
    private String sourceFileKey;

    @Column(name = "segment_start_ms")
    private Integer segmentStartMs;

    @Column(name = "segment_end_ms")
    private Integer segmentEndMs;

    @Column(name = "tts_text", length = 100)
    private String ttsText;

    @Column(name = "voice_id")
    private String voiceId;

    @Builder.Default
    private String format = "MP3";

    @Column(columnDefinition = "TEXT")
    private String prompt;

    @Column(length = 200)
    private String title;

    @Column(name = "file_id")
    private UUID fileId;

    @Column(name = "audio_url", columnDefinition = "TEXT")
    private String audioUrl;

    @Column(name = "preview_versions", columnDefinition = "TEXT")
    @Convert(converter = PreviewVersionsConverter.class)
    private List<PreviewVersion> previewVersions;

    @Column(name = "cache_key")
    private String cacheKey;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Builder.Default
    private boolean deleted = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
