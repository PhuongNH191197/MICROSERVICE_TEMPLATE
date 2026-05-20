package com.platform.audio.dto.request;
import jakarta.validation.constraints.*;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class DiyGenerateRequest {
    @NotBlank private String sourceFileKey;
    @NotNull private Integer segmentStartMs;
    @NotNull private Integer segmentEndMs;
    @NotBlank @Size(max = 100) private String text;
    @NotBlank private String voiceId;
    private String title;
}
