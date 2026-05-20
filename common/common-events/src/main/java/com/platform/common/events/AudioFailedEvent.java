package com.platform.common.events;
import lombok.*;
import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AudioFailedEvent {
    private String jobId;
    private String userId;
    private String errorMessage;
    private Integer retryCount;
    @Builder.Default private Instant occurredAt = Instant.now();
}
