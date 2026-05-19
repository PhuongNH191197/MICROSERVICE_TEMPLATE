package com.platform.file.dto;
import com.platform.file.entity.FileStatus;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data @Builder
public class FileMetadataResponse {
    private String id;
    private String fileKey;
    private String originalName;
    private String bucket;
    private String publicUrl;
    private long size;
    private String contentType;
    private FileStatus status;
    private Instant createdAt;
    private Instant confirmedAt;
}
