package com.platform.audio.service;

import com.platform.audio.client.ElevenLabsClient;
import com.platform.audio.client.VocalDetectorClient;
import com.platform.audio.config.RabbitMQConfig;
import com.platform.audio.dto.request.DiyAnalyzeRequest;
import com.platform.audio.dto.request.DiyGenerateRequest;
import com.platform.audio.dto.response.AnalyzeResponse;
import com.platform.audio.dto.response.AudioJobResponse;
import com.platform.audio.dto.response.TtsVoiceResponse;
import com.platform.audio.entity.AudioJob;
import com.platform.audio.entity.MusicLibrary;
import com.platform.audio.enums.JobStatus;
import com.platform.audio.enums.JobType;
import com.platform.audio.exception.InsufficientCreditException;
import com.platform.audio.exception.JobLimitExceededException;
import com.platform.audio.exception.VocalDetectedException;
import com.platform.audio.repository.AudioJobRepository;
import com.platform.audio.repository.MusicLibraryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiyServiceTest {

    @Mock VocalDetectorClient vocalDetectorClient;
    @Mock ElevenLabsClient elevenLabsClient;
    @Mock AudioJobRepository jobRepo;
    @Mock MusicLibraryRepository musicLibraryRepo;
    @Mock CreditService creditService;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock AiGenerateService aiGenerateService;
    @InjectMocks DiyService diyService;

    @Test
    @DisplayName("analyze - no vocal - returns result")
    void analyze_noVocal_returnsResult() {
        DiyAnalyzeRequest req = mock(DiyAnalyzeRequest.class);
        when(req.getFileKey()).thenReturn("bucket/music.mp3");
        AnalyzeResponse resp = AnalyzeResponse.builder().hasVocal(false).bpm(120.0).build();
        when(vocalDetectorClient.analyze("bucket/music.mp3")).thenReturn(resp);

        AnalyzeResponse result = diyService.analyze(req, "user1");

        assertThat(result.isHasVocal()).isFalse();
        assertThat(result.getBpm()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("analyze - vocal detected - throws VocalDetectedException")
    void analyze_hasVocal_throws() {
        DiyAnalyzeRequest req = mock(DiyAnalyzeRequest.class);
        when(req.getFileKey()).thenReturn("bucket/vocal.mp3");
        AnalyzeResponse resp = AnalyzeResponse.builder().hasVocal(true).build();
        when(vocalDetectorClient.analyze("bucket/vocal.mp3")).thenReturn(resp);

        assertThatThrownBy(() -> diyService.analyze(req, "user1"))
            .isInstanceOf(VocalDetectedException.class)
            .hasMessageContaining("vocal");
    }

    @Test
    @DisplayName("submitDiy - 5 active jobs - throws JobLimitExceededException")
    void submitDiy_jobLimitExceeded_throws() {
        when(jobRepo.countByUserIdAndStatusIn(eq("user1"), anyList())).thenReturn(5L);

        DiyGenerateRequest req = mockDiyRequest("Hello CRBT", "voice1", "My Title");
        assertThatThrownBy(() -> diyService.submitDiy(req, "user1"))
            .isInstanceOf(JobLimitExceededException.class)
            .hasMessageContaining("5");
        verifyNoInteractions(creditService, rabbitTemplate);
    }

    @Test
    @DisplayName("submitDiy - success - creates PENDING job and queues to RabbitMQ")
    void submitDiy_success_createsPendingAndQueues() {
        when(jobRepo.countByUserIdAndStatusIn(eq("user1"), anyList())).thenReturn(2L);
        AudioJob savedJob = AudioJob.builder()
            .id(UUID.randomUUID()).status(JobStatus.PENDING)
            .jobType(JobType.DIY_MIX).userId("user1").build();
        when(jobRepo.save(any())).thenReturn(savedJob);
        AudioJobResponse resp = AudioJobResponse.builder()
            .jobId(savedJob.getId().toString()).status(JobStatus.PENDING).build();
        when(aiGenerateService.toResponse(any())).thenReturn(resp);

        DiyGenerateRequest req = mockDiyRequest("Hello CRBT", "voice1", "My Title");
        AudioJobResponse result = diyService.submitDiy(req, "user1");

        assertThat(result.getStatus()).isEqualTo(JobStatus.PENDING);
        verify(creditService).deductCredit("user1", null);
        verify(rabbitTemplate).convertAndSend(anyString(), eq(RabbitMQConfig.ROUTING_DIY), anyString());
    }

    @Test
    @DisplayName("submitDiy - insufficient credit - throws before saving job")
    void submitDiy_insufficientCredit_throws() {
        when(jobRepo.countByUserIdAndStatusIn(eq("user1"), anyList())).thenReturn(0L);
        doThrow(new InsufficientCreditException("no credit")).when(creditService).deductCredit(any(), any());

        DiyGenerateRequest req = mockDiyRequest("text", "v1", "title");
        assertThatThrownBy(() -> diyService.submitDiy(req, "user1"))
            .isInstanceOf(InsufficientCreditException.class);
        verify(jobRepo, never()).save(any());
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("submitDiy - null title - derives title from text (up to 80 chars)")
    void submitDiy_nullTitle_derivedFromText() {
        when(jobRepo.countByUserIdAndStatusIn(eq("user1"), anyList())).thenReturn(0L);
        AudioJob savedJob = AudioJob.builder()
            .id(UUID.randomUUID()).status(JobStatus.PENDING).build();
        when(jobRepo.save(any())).thenReturn(savedJob);
        when(aiGenerateService.toResponse(any())).thenReturn(
            AudioJobResponse.builder().jobId(savedJob.getId().toString()).status(JobStatus.PENDING).build());

        DiyGenerateRequest req = mockDiyRequest("Short text", "v1", null);
        diyService.submitDiy(req, "user1");

        verify(jobRepo).save(argThat(job -> "Short text".equals(job.getTitle())));
    }

    @Test
    @DisplayName("submitDiy - long text - title truncated to 80 chars")
    void submitDiy_longText_titleTruncatedTo80() {
        when(jobRepo.countByUserIdAndStatusIn(eq("user1"), anyList())).thenReturn(0L);
        String longText = "A".repeat(120);
        AudioJob savedJob = AudioJob.builder()
            .id(UUID.randomUUID()).status(JobStatus.PENDING).build();
        when(jobRepo.save(any())).thenReturn(savedJob);
        when(aiGenerateService.toResponse(any())).thenReturn(
            AudioJobResponse.builder().jobId(savedJob.getId().toString()).status(JobStatus.PENDING).build());

        DiyGenerateRequest req = mockDiyRequest(longText, "v1", null);
        diyService.submitDiy(req, "user1");

        verify(jobRepo).save(argThat(job -> job.getTitle() != null && job.getTitle().length() == 80));
    }

    @Test
    @DisplayName("listLibrary - blank query - returns active tracks")
    void listLibrary_blankQuery_returnsActive() {
        Page<MusicLibrary> page = new PageImpl<>(List.of());
        when(musicLibraryRepo.findByActiveTrueOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(page);

        Page<MusicLibrary> result = diyService.listLibrary("", 0, 10);

        assertThat(result).isNotNull();
        verify(musicLibraryRepo).findByActiveTrueOrderByCreatedAtDesc(any(Pageable.class));
        verify(musicLibraryRepo, never()).searchByText(any(), any());
    }

    @Test
    @DisplayName("listLibrary - null query - returns active tracks")
    void listLibrary_nullQuery_returnsActive() {
        Page<MusicLibrary> page = new PageImpl<>(List.of());
        when(musicLibraryRepo.findByActiveTrueOrderByCreatedAtDesc(any(Pageable.class))).thenReturn(page);

        Page<MusicLibrary> result = diyService.listLibrary(null, 0, 10);

        assertThat(result).isNotNull();
        verify(musicLibraryRepo).findByActiveTrueOrderByCreatedAtDesc(any(Pageable.class));
    }

    @Test
    @DisplayName("listLibrary - with query - delegates to searchByText")
    void listLibrary_withQuery_searchesByText() {
        Page<MusicLibrary> page = new PageImpl<>(List.of());
        when(musicLibraryRepo.searchByText(eq("pop"), any(Pageable.class))).thenReturn(page);

        Page<MusicLibrary> result = diyService.listLibrary("pop", 0, 10);

        assertThat(result).isNotNull();
        verify(musicLibraryRepo).searchByText(eq("pop"), any(Pageable.class));
        verify(musicLibraryRepo, never()).findByActiveTrueOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("listVoices - delegates to ElevenLabsClient")
    void listVoices_delegatesToClient() {
        List<TtsVoiceResponse> voices = List.of(
            TtsVoiceResponse.builder().voiceId("v1").name("Alice").language("vi").build(),
            TtsVoiceResponse.builder().voiceId("v2").name("Bob").language("en").build());
        when(elevenLabsClient.listVoices()).thenReturn(voices);

        List<TtsVoiceResponse> result = diyService.listVoices();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getVoiceId()).isEqualTo("v1");
    }

    private DiyGenerateRequest mockDiyRequest(String text, String voiceId, String title) {
        DiyGenerateRequest req = mock(DiyGenerateRequest.class);
        lenient().when(req.getText()).thenReturn(text);
        lenient().when(req.getVoiceId()).thenReturn(voiceId);
        lenient().when(req.getTitle()).thenReturn(title);
        lenient().when(req.getSourceFileKey()).thenReturn("bucket/track.mp3");
        lenient().when(req.getSegmentStartMs()).thenReturn(0);
        lenient().when(req.getSegmentEndMs()).thenReturn(30000);
        return req;
    }
}
