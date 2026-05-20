package com.platform.common.events;
import lombok.*;
import java.time.Instant;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AudioGeneratedEvent {
    private String jobId;
    private String userId;
    private String jobType;
    private String title;
    private String audioUrl;
    private List<String> previewUrls;
    private Integer durationSeconds;
    @Builder.Default private Instant occurredAt = Instant.now();
}
