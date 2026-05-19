package com.platform.file.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfirmUploadRequest {
    @NotBlank private String fileKey;
}
