package com.platform.file.dto;
import lombok.Builder;
import lombok.Data;

@Data @Builder
public class PresignedUrlResponse {
    private String presignedUrl;
    private String fileKey;
    private int expiresIn;
}
