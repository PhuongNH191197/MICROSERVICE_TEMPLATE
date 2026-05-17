package com.platform.userprofile.dto;
import lombok.Data;

@Data
public class FileUploadResponse {
    private String fileId;
    private String url;
    private String fileName;
    private long size;
    private String contentType;
}
