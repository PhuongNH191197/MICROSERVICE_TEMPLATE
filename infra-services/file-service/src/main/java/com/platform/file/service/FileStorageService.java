package com.platform.file.service;
import com.platform.common.events.FileUploadedEvent;
import com.platform.file.config.RabbitMQConfig;
import com.platform.file.dto.*;
import com.platform.file.entity.FileRecord;
import com.platform.file.entity.FileStatus;
import com.platform.file.repository.FileRecordRepository;
import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j @Service @RequiredArgsConstructor
public class FileStorageService {
    private final MinioClient minioClient;
    private final FileRecordRepository fileRecordRepository;
    private final RabbitTemplate rabbitTemplate;
    // Thêm fields
    private Set<String> allowedImageTypeSet;
    private Set<String> allowedAudioTypeSet;
    @Value("${file.allowed-types.image}") private String allowedImageTypes;
    @Value("${file.allowed-types.audio}") private String allowedAudioTypes;
    @Value("${file.max-size-mb.image:5}") private long maxImageSizeMb;
    @Value("${file.max-size-mb.audio:50}") private long maxAudioSizeMb;
    @Value("${file.presigned-url-expiry-hours:1}") private int presignedGetExpiryHours;
    @Value("${file.presigned-put-url-expiry-minutes:5}") private int presignedPutExpiryMinutes;
    @Value("${file.bucket.images:media-images}") private String imagesBucket;
    @Value("${file.bucket.audio:media-audio}") private String audioBucket;
    @Value("${file.bucket.temp:media-temp}") private String tempBucket;
    @Value("${file.bucket.private:media-private}") private String privateBucket;
    // Thay vì dùng @PostConstruct, khởi tạo lazy trong validate method
    private Set<String> getAllowedImageTypes() {
        if (allowedImageTypeSet == null)
            allowedImageTypeSet = Set.of(allowedImageTypes.split(","));
        return allowedImageTypeSet;
    }
    // Thêm bên dưới getAllowedImageTypes()
    private Set<String> getAllowedAudioTypes() {
        if (allowedAudioTypeSet == null)
            allowedAudioTypeSet = Set.of(allowedAudioTypes.split(","));
        return allowedAudioTypeSet;
    }
    @Transactional
    public FileUploadResponse upload(MultipartFile file, String userId) throws Exception {
        validateImageFile(file);
        ensureBucket(imagesBucket);
        String fileKey = "avatars/" + UUID.randomUUID() + "_" + sanitizeFilename(file.getOriginalFilename());
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(imagesBucket).object(fileKey)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType()).build());

        String url = buildPresignedGetUrl(imagesBucket, fileKey);
        FileRecord record = fileRecordRepository.save(FileRecord.builder()
                .fileKey(fileKey).uploaderId(userId)
                .originalName(file.getOriginalFilename())
                .bucket(imagesBucket).size(file.getSize())
                .contentType(file.getContentType())
                .status(FileStatus.CONFIRMED)
                .confirmedAt(Instant.now())
                .publicFile(true).publicUrl(url).build());

        log.info("Image uploaded: fileKey={} userId={}", fileKey, userId);
        rabbitTemplate.convertAndSend(RabbitMQConfig.FILE_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY_FILE_UPLOADED,
                FileUploadedEvent.builder().fileId(record.getId().toString())
                        .userId(userId).fileName(file.getOriginalFilename()).url(url).build());

        return FileUploadResponse.builder()
                .fileId(record.getId().toString()).url(url)
                .fileName(file.getOriginalFilename())
                .size(file.getSize()).contentType(file.getContentType()).build();
    }

    @Transactional
    public PresignedUrlResponse createPresignedPutUrl(PresignedUrlRequest request, String userId) throws Exception {
        validatePresignedRequest(request);
        ensureBucket(tempBucket);
        String fileKey = "temp/" + UUID.randomUUID() + "_" + sanitizeFilename(request.getFilename());
        String presignedUrl = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT).bucket(tempBucket).object(fileKey)
                .expiry(presignedPutExpiryMinutes, TimeUnit.MINUTES).build());

        Instant expiresAt = Instant.now().plus(presignedPutExpiryMinutes, ChronoUnit.MINUTES);
        FileRecord record = FileRecord.builder()
                .fileKey(fileKey).uploaderId(userId)
                .originalName(request.getFilename())
                .bucket(tempBucket).size(request.getFileSize())
                .contentType(request.getContentType())
                .status(FileStatus.PENDING)
                .expiresAt(expiresAt).build();
        fileRecordRepository.save(record);

        log.info("Presigned PUT issued: fileKey={} userId={}", fileKey, userId);
        return PresignedUrlResponse.builder()
                .presignedUrl(presignedUrl).fileKey(fileKey)
                .expiresIn(presignedPutExpiryMinutes * 60).build();
    }

    @Transactional
    public FileMetadataResponse confirmUpload(ConfirmUploadRequest request, String userId) throws Exception {
        FileRecord record = fileRecordRepository
                .findByFileKeyAndStatus(request.getFileKey(), FileStatus.PENDING)
                .orElseThrow(() -> new IllegalArgumentException(
                        "File not found or already confirmed: " + request.getFileKey()));

        if (!userId.equals(record.getUploaderId()))
            throw new IllegalArgumentException("Access denied: not file owner");
        if (record.getExpiresAt() != null && Instant.now().isAfter(record.getExpiresAt())) {
            record.setStatus(FileStatus.FAILED);
            fileRecordRepository.save(record);
            throw new IllegalArgumentException("Upload window expired");
        }

        String destBucket = resolveDestBucket(record.getContentType());
        String destKey = resolveDestPrefix(record.getContentType())
                + "/" + UUID.randomUUID() + "_" + sanitizeFilename(record.getOriginalName());
        ensureBucket(destBucket);
        minioClient.copyObject(CopyObjectArgs.builder()
                .bucket(destBucket).object(destKey)
                .source(CopySource.builder().bucket(tempBucket).object(record.getFileKey()).build())
                .build());

        record.setFileKey(destKey);
        record.setBucket(destBucket);
        record.setStatus(FileStatus.CONFIRMED);
        record.setConfirmedAt(Instant.now());
        fileRecordRepository.save(record);

        String destUrl = buildPresignedGetUrl(destBucket, destKey);
        log.info("Upload confirmed: fileKey={} userId={} destBucket={}", destKey, userId, destBucket);
        rabbitTemplate.convertAndSend(RabbitMQConfig.FILE_EXCHANGE,
                RabbitMQConfig.ROUTING_KEY_FILE_UPLOADED,
                FileUploadedEvent.builder().fileId(record.getId().toString())
                        .userId(userId).fileName(record.getOriginalName()).url(destUrl).build());

        return toMetadataResponse(record);
    }

    public String getPresignedUrl(String fileId) throws Exception {
        FileRecord record = fileRecordRepository.findByIdAndDeletedFalse(UUID.fromString(fileId))
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        return buildPresignedGetUrl(record.getBucket(), record.getFileKey() != null
                ? record.getFileKey() : record.getStoredName());
    }

    @Transactional
    public void delete(String fileId, String userId) {
        FileRecord record = fileRecordRepository.findByIdAndDeletedFalse(UUID.fromString(fileId))
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        if (!userId.equals(record.getUploaderId()))
            throw new IllegalArgumentException("Access denied: not file owner");
        record.setStatus(FileStatus.DELETED);
        record.setDeleted(true);
        fileRecordRepository.save(record);
    }

    public List<FileMetadataResponse> getUserFiles(String userId) {
        return fileRecordRepository.findByUploaderIdAndStatus(userId, FileStatus.CONFIRMED)
                .stream().map(this::toMetadataResponse).toList();
    }

    public FileMetadataResponse toMetadataResponse(FileRecord record) {
        return FileMetadataResponse.builder()
                .id(record.getId().toString()).fileKey(record.getFileKey())
                .originalName(record.getOriginalName()).bucket(record.getBucket())
                .publicUrl(record.getPublicUrl()).size(record.getSize() != null ? record.getSize() : 0)
                .contentType(record.getContentType()).status(record.getStatus())
                .createdAt(record.getCreatedAt()).confirmedAt(record.getConfirmedAt()).build();
    }

    private void validateImageFile(MultipartFile file) {
        if (file.isEmpty()) throw new IllegalArgumentException("Empty file");
        if (file.getSize() > maxImageSizeMb * 1024 * 1024)
            throw new IllegalArgumentException("Image exceeds " + maxImageSizeMb + "MB limit");
        if (!getAllowedImageTypes().contains(file.getContentType()))
            throw new IllegalArgumentException("Content-type not allowed for image upload: " + file.getContentType());
    }

    private void validatePresignedRequest(PresignedUrlRequest request) {
        if (!getAllowedAudioTypes().contains(request.getContentType()))
            throw new IllegalArgumentException("Content-type not allowed: " + request.getContentType());
        if (request.getFileSize() > maxAudioSizeMb * 1024 * 1024)
            throw new IllegalArgumentException("File size exceeds " + maxAudioSizeMb + "MB limit");
    }

    private String resolveDestBucket(String contentType) {
        if (contentType != null && contentType.startsWith("audio/")) return audioBucket;
        return privateBucket;
    }

    private String resolveDestPrefix(String contentType) {
        if (contentType != null && contentType.startsWith("audio/")) return "audio";
        return "files";
    }

    private String buildPresignedGetUrl(String bucket, String objectKey) throws Exception {
        return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET).bucket(bucket).object(objectKey)
                .expiry(presignedGetExpiryHours, TimeUnit.HOURS).build());
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "file";
        String name = java.nio.file.Paths.get(filename).getFileName().toString();
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void ensureBucket(String bucket) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            try {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            } catch (io.minio.errors.ErrorResponseException e) {
                if (!e.errorResponse().code().contains("BucketAlready")) throw e;
            }
        }
    }
}
