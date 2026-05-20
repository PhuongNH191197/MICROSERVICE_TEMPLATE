package com.platform.audio.dto.response;
import com.platform.audio.enums.JobStatus;
import com.platform.audio.enums.JobType;
import lombok.*;
import java.time.Instant;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AudioJobResponse {
    private String jobId;
    private JobStatus status;
    private JobType jobType;
    private String mode;
    private String format;
    private String prompt;
    private String title;
    private String genre;
    private String mood;
    private String instrument;
    private String audioUrl;
    private List<PreviewVersion> previewVersions;
    private Integer durationSeconds;
    private Long fileSizeBytes;
    private Integer retryCount;
    private String errorMessage;
    private Instant createdAt;
    private Instant completedAt;
    private Integer estimatedSeconds;
}
