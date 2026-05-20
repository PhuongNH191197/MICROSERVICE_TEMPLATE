package com.platform.audio.controller;

import com.platform.audio.dto.response.AudioJobResponse;
import com.platform.audio.entity.AudioJob;
import com.platform.audio.enums.JobStatus;
import com.platform.audio.enums.JobType;
import com.platform.audio.exception.AudioJobNotFoundException;
import com.platform.audio.repository.AudioJobRepository;
import com.platform.audio.service.AiGenerateService;
import com.platform.audio.service.CreditService;
import com.platform.audio.service.MinioAudioService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AudioJobControllerTest {

    @Mock AudioJobRepository jobRepo;
    @Mock AiGenerateService aiGenerateService;
    @Mock CreditService creditService;
    @Mock MinioAudioService minioService;
    @InjectMocks AudioJobController controller;

    @Test
    @DisplayName("listJobs - returns paged results")
    void listJobs_returnsPaged() {
        AudioJob job = buildJob(JobStatus.COMPLETED);
        Page<AudioJob> page = new PageImpl<>(List.of(job));
        when(jobRepo.findByUserIdAndDeletedFalseOrderByCreatedAtDesc(eq("user1"), any(Pageable.class)))
            .thenReturn(page);
        when(aiGenerateService.toResponse(job)).thenReturn(
            AudioJobResponse.builder().jobId(job.getId().toString()).status(JobStatus.COMPLETED).build());

        ResponseEntity<?> response = controller.listJobs("user1", 0, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("cancelOrDelete - PENDING job - cancels and refunds credit")
    void cancelOrDelete_pendingJob_cancelsAndRefunds() {
        AudioJob job = buildJob(JobStatus.PENDING);
        when(jobRepo.findByIdAndUserId(job.getId(), "user1")).thenReturn(Optional.of(job));
        when(jobRepo.save(any())).thenReturn(job);

        ResponseEntity<?> response = controller.cancelOrDelete(job.getId().toString(), "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        verify(creditService).refundCredit("user1", job.getId());
    }

    @Test
    @DisplayName("cancelOrDelete - PENDING job - refund failure is swallowed")
    void cancelOrDelete_pendingJob_refundFailureSwallowed() {
        AudioJob job = buildJob(JobStatus.PENDING);
        when(jobRepo.findByIdAndUserId(job.getId(), "user1")).thenReturn(Optional.of(job));
        when(jobRepo.save(any())).thenReturn(job);
        doThrow(new RuntimeException("DB down")).when(creditService).refundCredit(any(), any());

        ResponseEntity<?> response = controller.cancelOrDelete(job.getId().toString(), "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancelOrDelete - COMPLETED job - soft deletes")
    void cancelOrDelete_completedJob_softDeletes() {
        AudioJob job = buildJob(JobStatus.COMPLETED);
        when(jobRepo.findByIdAndUserId(job.getId(), "user1")).thenReturn(Optional.of(job));
        when(jobRepo.save(any())).thenReturn(job);

        ResponseEntity<?> response = controller.cancelOrDelete(job.getId().toString(), "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(job.isDeleted()).isTrue();
        verifyNoInteractions(creditService);
    }

    @Test
    @DisplayName("cancelOrDelete - FAILED job - soft deletes")
    void cancelOrDelete_failedJob_softDeletes() {
        AudioJob job = buildJob(JobStatus.FAILED);
        when(jobRepo.findByIdAndUserId(job.getId(), "user1")).thenReturn(Optional.of(job));
        when(jobRepo.save(any())).thenReturn(job);

        ResponseEntity<?> response = controller.cancelOrDelete(job.getId().toString(), "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(job.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("cancelOrDelete - PROCESSING job - returns 409 Conflict")
    void cancelOrDelete_processingJob_returns409() {
        AudioJob job = buildJob(JobStatus.PROCESSING);
        when(jobRepo.findByIdAndUserId(job.getId(), "user1")).thenReturn(Optional.of(job));

        ResponseEntity<?> response = controller.cancelOrDelete(job.getId().toString(), "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(409));
        verify(jobRepo, never()).save(any());
    }

    @Test
    @DisplayName("cancelOrDelete - not found - throws AudioJobNotFoundException")
    void cancelOrDelete_notFound_throws() {
        UUID jobId = UUID.randomUUID();
        when(jobRepo.findByIdAndUserId(eq(jobId), eq("user1"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.cancelOrDelete(jobId.toString(), "user1"))
            .isInstanceOf(AudioJobNotFoundException.class);
    }

    @Test
    @DisplayName("getAudio - COMPLETED job with audioUrl - returns 302 redirect")
    void getAudio_completed_returns302() throws Exception {
        AudioJob job = buildJob(JobStatus.COMPLETED);
        job.setAudioUrl("http://minio/audio/job.mp3");
        when(jobRepo.findByIdAndUserId(job.getId(), "user1")).thenReturn(Optional.of(job));
        when(minioService.generatePresignedGet(anyString())).thenReturn("http://minio/presigned/job.mp3");

        ResponseEntity<?> response = controller.getAudio(job.getId().toString(), "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(302));
    }

    @Test
    @DisplayName("getAudio - PENDING job - returns 404 not ready")
    void getAudio_notReady_returns404() throws Exception {
        AudioJob job = buildJob(JobStatus.PENDING);
        when(jobRepo.findByIdAndUserId(job.getId(), "user1")).thenReturn(Optional.of(job));

        ResponseEntity<?> response = controller.getAudio(job.getId().toString(), "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(404));
    }

    @Test
    @DisplayName("getDiyVersion - COMPLETED with previews - returns 302 redirect")
    void getDiyVersion_completed_returns302() throws Exception {
        AudioJob job = buildJob(JobStatus.COMPLETED);
        job.setPreviewVersions(List.of());
        when(jobRepo.findByIdAndUserId(job.getId(), "user1")).thenReturn(Optional.of(job));
        when(minioService.generatePresignedGet(anyString())).thenReturn("http://minio/presigned/v1.mp3");

        ResponseEntity<?> response = controller.getDiyVersion(job.getId().toString(), "voice_only", "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(302));
    }

    @Test
    @DisplayName("getDiyVersion - PENDING job - returns 404 not ready")
    void getDiyVersion_notReady_returns404() throws Exception {
        AudioJob job = buildJob(JobStatus.PENDING);
        when(jobRepo.findByIdAndUserId(job.getId(), "user1")).thenReturn(Optional.of(job));

        ResponseEntity<?> response = controller.getDiyVersion(job.getId().toString(), "voice_only", "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(404));
    }

    @Test
    @DisplayName("topup - returns updated balance")
    void topup_returnsUpdatedBalance() {
        when(creditService.getBalance("user1")).thenReturn(15);

        ResponseEntity<?> response = controller.topup("user1", 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(creditService).topup("user1", 10);
        verify(creditService).getBalance("user1");
    }

    @Test
    @DisplayName("balance - returns current balance")
    void balance_returnsBalance() {
        when(creditService.getBalance("user1")).thenReturn(5);

        ResponseEntity<?> response = controller.balance("user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(creditService).getBalance("user1");
    }

    private AudioJob buildJob(JobStatus status) {
        return AudioJob.builder()
            .id(UUID.randomUUID())
            .userId("user1")
            .jobType(JobType.AI_GENERATE)
            .status(status)
            .deleted(false)
            .build();
    }
}
