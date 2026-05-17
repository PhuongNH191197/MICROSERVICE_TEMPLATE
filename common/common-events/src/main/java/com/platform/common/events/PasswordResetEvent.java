package com.platform.common.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PasswordResetEvent {
    private String userId;
    private String email;
    private String resetToken;
    @Builder.Default private Instant occurredAt = Instant.now();
}
