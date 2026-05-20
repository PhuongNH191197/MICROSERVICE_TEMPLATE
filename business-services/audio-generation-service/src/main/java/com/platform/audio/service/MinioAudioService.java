package com.platform.audio.service;
import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@Service @RequiredArgsConstructor @Slf4j
public class MinioAudioService {
    private final MinioClient minioClient;

    @Value("${minio.bucket:media-audio}")
    private String bucket;

    @Value("${audio.presigned-url-expiry-hours:1}")
    private int presignedExpiryHours;

    public String uploadAudio(byte[] audioBytes, String userId, String objectSuffix, String contentType) throws Exception {
        String objectName = userId + "/" + objectSuffix;
        minioClient.putObject(PutObjectArgs.builder()
            .bucket(bucket).object(objectName)
            .stream(new ByteArrayInputStream(audioBytes), audioBytes.length, -1)
            .contentType(contentType).build());
        log.info("Uploaded audio to MinIO: bucket={} object={}", bucket, objectName);
        return objectName;
    }

    public String generatePresignedGet(String objectName) throws Exception {
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
            .bucket(bucket).object(objectName)
            .method(Method.GET)
            .expiry(presignedExpiryHours, TimeUnit.HOURS).build());
    }

    public Path downloadToTemp(String fileKey) throws Exception {
        if (fileKey.contains("..") || fileKey.startsWith("/")) {
            throw new IllegalArgumentException("Invalid file key: " + fileKey);
        }
        Path tmp = Files.createTempFile("source_", ".mp3");
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(fileKey).build())) {
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Downloaded {} from MinIO to temp file {}", fileKey, tmp);
        return tmp;
    }
}
