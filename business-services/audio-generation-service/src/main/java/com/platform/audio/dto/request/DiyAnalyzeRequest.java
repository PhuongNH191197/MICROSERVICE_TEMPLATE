package com.platform.audio.dto.request;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class DiyAnalyzeRequest {
    @NotBlank private String fileKey;
}
