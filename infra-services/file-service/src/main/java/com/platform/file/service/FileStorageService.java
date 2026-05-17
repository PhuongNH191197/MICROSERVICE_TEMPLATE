package com.platform.file.service;
import com.platform.common.events.FileUploadedEvent;
import com.platform.file.config.RabbitMQConfig;
import com.platform.file.dto.FileUploadResponse;
import com.platform.file.entity.FileRecord;
import com.platform.file.repository.FileRecordRepository;
import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j @Service @RequiredArgsConstructor
public class FileStorageService {
    private final MinioClient minioClient;
    private final FileRecordRepository fileRecordRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${file.allowed-types:image/jpeg,image/png,application/pdf}") private String allowedTypes;
    @Value("${file.presigned-url-expiry-hours:1}") private int presignedUrlExpiryHours;
    private static final String BUCKET = "documents";

    @Transactional
    public FileUploadResponse upload(MultipartFile file, String userId) throws Exception {
        validateFile(file);
        ensureBucket(BUCKET);
        String storedName = UUID.randomUUID() + "-" + file.getOriginalFilename();
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(BUCKET).object(storedName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType()).build());

        FileRecord record = FileRecord.builder()
                .userId(userId).originalName(file.getOriginalFilename())
                .storedName(storedName).bucket(BUCKET)
                .size(file.getSize()).contentType(file.getContentType()).build();
        record = fileRecordRepository.save(record);

        String url = getPresignedUrl(record.getId().toString());
        rabbitTemplate.convertAndSend(RabbitMQConfig.FILE_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY_FILE_UPLOADED,
                FileUploadedEvent.builder().fileId(record.getId().toString())
                        .userId(userId).fileName(file.getOriginalFilename()).url(url).build());

        return FileUploadResponse.builder().fileId(record.getId().toString())
                .url(url).fileName(file.getOriginalFilename())
                .size(file.getSize()).contentType(file.getContentType()).build();
    }

    public String getPresignedUrl(String fileId) throws Exception {
        FileRecord record = fileRecordRepository.findByIdAndDeletedFalse(UUID.fromString(fileId))
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET).bucket(record.getBucket()).object(record.getStoredName())
                .expiry(presignedUrlExpiryHours, TimeUnit.HOURS).build());
    }

    @Transactional
    public void delete(String fileId) {
        FileRecord record = fileRecordRepository.findByIdAndDeletedFalse(UUID.fromString(fileId))
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        record.setDeleted(true);
        fileRecordRepository.save(record);
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) throw new IllegalArgumentException("Empty file");
        if (file.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("File exceeds 10MB limit");
        List<String> allowed = Arrays.asList(allowedTypes.split(","));
        if (!allowed.contains(file.getContentType())) throw new IllegalArgumentException("File type not allowed");
    }

    private void ensureBucket(String bucket) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }
}
