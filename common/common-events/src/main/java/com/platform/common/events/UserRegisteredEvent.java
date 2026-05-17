package com.platform.common.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserRegisteredEvent {
    private String userId;
    private String email;
    private String fullName;
    @Builder.Default private Instant occurredAt = Instant.now();
}
