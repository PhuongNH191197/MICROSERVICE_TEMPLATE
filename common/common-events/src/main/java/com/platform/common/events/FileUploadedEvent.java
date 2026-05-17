package com.platform.common.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FileUploadedEvent {
    private String fileId;
    private String userId;
    private String fileName;
    private String url;
    @Builder.Default private Instant occurredAt = Instant.now();
}
