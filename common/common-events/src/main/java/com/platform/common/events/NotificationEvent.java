package com.platform.common.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationEvent {
    private String type;
    private String recipient;
    private String subject;
    private String body;
    private Map<String, Object> metadata;
    @Builder.Default private Instant occurredAt = Instant.now();
}
