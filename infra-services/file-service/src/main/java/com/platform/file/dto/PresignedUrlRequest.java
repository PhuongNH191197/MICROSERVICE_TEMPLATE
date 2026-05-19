package com.platform.file.dto;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class PresignedUrlRequest {
    @NotBlank private String filename;
    @Positive @Max(52428800) private long fileSize;
    @NotBlank private String contentType;
}
