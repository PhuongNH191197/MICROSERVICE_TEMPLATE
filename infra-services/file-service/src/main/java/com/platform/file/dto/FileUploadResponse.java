package com.platform.file.dto;
import lombok.Builder;
import lombok.Data;

@Data @Builder
public class FileUploadResponse {
    private String fileId;
    private String url;
    private String fileName;
    private long size;
    private String contentType;
}
