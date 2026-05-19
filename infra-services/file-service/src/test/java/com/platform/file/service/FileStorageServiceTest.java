package com.platform.file.service;

import com.platform.common.events.FileUploadedEvent;
import com.platform.file.config.RabbitMQConfig;
import com.platform.file.dto.*;
import com.platform.file.entity.FileRecord;
import com.platform.file.entity.FileStatus;
import com.platform.file.repository.FileRecordRepository;
import io.minio.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileStorageServiceTest {

    @Mock private MinioClient minioClient;
    @Mock private FileRecordRepository fileRecordRepository;
    @Mock private RabbitTemplate rabbitTemplate;

    @InjectMocks private FileStorageService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "allowedImageTypes", "image/jpeg,image/png");
        ReflectionTestUtils.setField(service, "allowedAudioTypes", "audio/mpeg,audio/wav");
        ReflectionTestUtils.setField(service, "maxImageSizeMb", 5L);
        ReflectionTestUtils.setField(service, "maxAudioSizeMb", 50L);
        ReflectionTestUtils.setField(service, "presignedGetExpiryHours", 1);
        ReflectionTestUtils.setField(service, "presignedPutExpiryMinutes", 5);
        ReflectionTestUtils.setField(service, "imagesBucket", "media-images");
        ReflectionTestUtils.setField(service, "audioBucket", "media-audio");
        ReflectionTestUtils.setField(service, "tempBucket", "media-temp");
        ReflectionTestUtils.setField(service, "privateBucket", "media-private");
    }

    // --- upload() ---

    @Test
    void upload_success() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[100]);
        UUID id = UUID.randomUUID();
        FileRecord saved = FileRecord.builder()
                .id(id).fileKey("avatars/test.jpg").uploaderId("user1")
                .originalName("test.jpg").bucket("media-images")
                .size(100L).contentType("image/jpeg")
                .status(FileStatus.CONFIRMED).confirmedAt(Instant.now()).build();

        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(fileRecordRepository.save(any())).thenReturn(saved);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("http://minio/test.jpg");

        FileUploadResponse result = service.upload(file, "user1");

        assertThat(result.getFileId()).isEqualTo(id.toString());
        assertThat(result.getUrl()).isEqualTo("http://minio/test.jpg");
        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.FILE_EXCHANGE),
                eq(RabbitMQConfig.ROUTING_KEY_FILE_UPLOADED),
                any(FileUploadedEvent.class));
    }

    @Test
    void upload_emptyFile_throws() {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.upload(file, "user1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Empty file");
    }

    @Test
    void upload_exceedsSizeLimit_throws() {
        byte[] big = new byte[(int) (6L * 1024 * 1024)];
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", big);

        assertThatThrownBy(() -> service.upload(file, "user1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5MB");
    }

    @Test
    void upload_disallowedContentType_throws() {
        MockMultipartFile file = new MockMultipartFile("file", "test.gif", "image/gif", new byte[100]);

        assertThatThrownBy(() -> service.upload(file, "user1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void upload_createsBucketWhenMissing() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[100]);
        FileRecord saved = FileRecord.builder()
                .id(UUID.randomUUID()).fileKey("avatars/test.jpg").uploaderId("u")
                .originalName("test.jpg").bucket("media-images").size(100L)
                .contentType("image/jpeg").status(FileStatus.CONFIRMED).confirmedAt(Instant.now()).build();

        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);
        when(fileRecordRepository.save(any())).thenReturn(saved);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("http://minio/test.jpg");

        service.upload(file, "u");

        verify(minioClient).makeBucket(any(MakeBucketArgs.class));
    }

    // --- createPresignedPutUrl() ---

    @Test
    void createPresignedPutUrl_success() throws Exception {
        PresignedUrlRequest req = new PresignedUrlRequest();
        req.setFilename("audio.mp3");
        req.setFileSize(1024L);
        req.setContentType("audio/mpeg");

        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("http://minio/presigned");
        when(fileRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PresignedUrlResponse result = service.createPresignedPutUrl(req, "user1");

        assertThat(result.getPresignedUrl()).isEqualTo("http://minio/presigned");
        assertThat(result.getFileKey()).contains("audio.mp3");
        assertThat(result.getExpiresIn()).isEqualTo(300);
    }

    @Test
    void createPresignedPutUrl_disallowedType_throws() {
        PresignedUrlRequest req = new PresignedUrlRequest();
        req.setFilename("file.xyz");
        req.setFileSize(1024L);
        req.setContentType("application/octet-stream");

        assertThatThrownBy(() -> service.createPresignedPutUrl(req, "user1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void createPresignedPutUrl_exceedsAudioLimit_throws() {
        PresignedUrlRequest req = new PresignedUrlRequest();
        req.setFilename("big.mp3");
        req.setFileSize(51L * 1024 * 1024);
        req.setContentType("audio/mpeg");

        assertThatThrownBy(() -> service.createPresignedPutUrl(req, "user1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50MB");
    }

    // --- confirmUpload() ---

    @Test
    void confirmUpload_success() throws Exception {
        UUID id = UUID.randomUUID();
        FileRecord pending = FileRecord.builder()
                .id(id).fileKey("temp/uuid_audio.mp3").uploaderId("user1")
                .originalName("audio.mp3").bucket("media-temp").size(1024L)
                .contentType("audio/mpeg").status(FileStatus.PENDING)
                .expiresAt(Instant.now().plusSeconds(300)).build();

        ConfirmUploadRequest req = new ConfirmUploadRequest();
        req.setFileKey("temp/uuid_audio.mp3");

        when(fileRecordRepository.findByFileKeyAndStatus("temp/uuid_audio.mp3", FileStatus.PENDING))
                .thenReturn(Optional.of(pending));
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        when(fileRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        FileMetadataResponse result = service.confirmUpload(req, "user1");

        assertThat(result.getStatus()).isEqualTo(FileStatus.CONFIRMED);
        verify(minioClient).copyObject(any(CopyObjectArgs.class));
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), any(FileUploadedEvent.class));
    }

    @Test
    void confirmUpload_notFound_throws() {
        ConfirmUploadRequest req = new ConfirmUploadRequest();
        req.setFileKey("temp/nonexistent");

        when(fileRecordRepository.findByFileKeyAndStatus("temp/nonexistent", FileStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmUpload(req, "user1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void confirmUpload_expired_throws() {
        FileRecord expired = FileRecord.builder()
                .id(UUID.randomUUID()).fileKey("temp/uuid_audio.mp3").uploaderId("user1")
                .originalName("audio.mp3").bucket("media-temp").size(1024L)
                .contentType("audio/mpeg").status(FileStatus.PENDING)
                .expiresAt(Instant.now().minusSeconds(60)).build();

        ConfirmUploadRequest req = new ConfirmUploadRequest();
        req.setFileKey("temp/uuid_audio.mp3");

        when(fileRecordRepository.findByFileKeyAndStatus("temp/uuid_audio.mp3", FileStatus.PENDING))
                .thenReturn(Optional.of(expired));
        when(fileRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThatThrownBy(() -> service.confirmUpload(req, "user1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    // --- getPresignedUrl() ---

    @Test
    void getPresignedUrl_success() throws Exception {
        UUID id = UUID.randomUUID();
        FileRecord record = FileRecord.builder()
                .id(id).fileKey("media-images/test.jpg").bucket("media-images")
                .status(FileStatus.CONFIRMED).build();

        when(fileRecordRepository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(record));
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("http://minio/signed");

        String url = service.getPresignedUrl(id.toString());

        assertThat(url).isEqualTo("http://minio/signed");
    }

    @Test
    void getPresignedUrl_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(fileRecordRepository.findByIdAndDeletedFalse(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPresignedUrl(id.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File not found");
    }

    // --- delete() ---

    @Test
    void delete_success() {
        UUID id = UUID.randomUUID();
        FileRecord record = FileRecord.builder()
                .id(id).fileKey("k").bucket("b").uploaderId("user1")
                .status(FileStatus.CONFIRMED).build();

        when(fileRecordRepository.findByIdAndDeletedFalse(id)).thenReturn(Optional.of(record));
        when(fileRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.delete(id.toString(), "user1");

        assertThat(record.getStatus()).isEqualTo(FileStatus.DELETED);
        assertThat(record.isDeleted()).isTrue();
        verify(fileRecordRepository).save(record);
    }

    @Test
    void delete_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(fileRecordRepository.findByIdAndDeletedFalse(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id.toString(), "user1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File not found");
    }

    // --- toMetadataResponse() ---

    @Test
    void toMetadataResponse_mapsAllFields() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        FileRecord record = FileRecord.builder()
                .id(id).fileKey("k").originalName("name.jpg").bucket("media-images")
                .publicUrl("http://url").size(42L).contentType("image/jpeg")
                .status(FileStatus.CONFIRMED).createdAt(now).confirmedAt(now).build();

        FileMetadataResponse resp = service.toMetadataResponse(record);

        assertThat(resp.getId()).isEqualTo(id.toString());
        assertThat(resp.getFileKey()).isEqualTo("k");
        assertThat(resp.getOriginalName()).isEqualTo("name.jpg");
        assertThat(resp.getSize()).isEqualTo(42L);
        assertThat(resp.getStatus()).isEqualTo(FileStatus.CONFIRMED);
        assertThat(resp.getPublicUrl()).isEqualTo("http://url");
    }
}
