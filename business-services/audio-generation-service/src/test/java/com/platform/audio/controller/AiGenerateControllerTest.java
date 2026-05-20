package com.platform.audio.controller;

import com.platform.audio.dto.request.AiGenerateRequest;
import com.platform.audio.dto.response.AudioJobResponse;
import com.platform.audio.enums.Genre;
import com.platform.audio.enums.Instrument;
import com.platform.audio.enums.JobStatus;
import com.platform.audio.enums.JobType;
import com.platform.audio.enums.Mood;
import com.platform.audio.exception.AudioJobNotFoundException;
import com.platform.audio.service.AiGenerateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiGenerateControllerTest {

    @Mock AiGenerateService aiGenerateService;
    @InjectMocks AiGenerateController controller;

    @Test
    @DisplayName("generate - cache HIT - responds 200 OK")
    void generate_cacheHit_returns200() {
        AiGenerateRequest req = new AiGenerateRequest(Genre.POP, Mood.HAPPY, Instrument.PIANO, "My CRBT");
        AudioJobResponse jobResp = AudioJobResponse.builder()
            .jobId(UUID.randomUUID().toString())
            .status(JobStatus.COMPLETED)
            .jobType(JobType.AI_GENERATE)
            .audioUrl("http://minio/audio/test.mp3")
            .build();
        when(aiGenerateService.submit(req, "user1")).thenReturn(jobResp);

        ResponseEntity<?> response = controller.generate(req, "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(aiGenerateService).submit(req, "user1");
    }

    @Test
    @DisplayName("generate - cache MISS - responds 202 Accepted")
    void generate_cacheMiss_returns202() {
        AiGenerateRequest req = new AiGenerateRequest(Genre.JAZZ, Mood.RELAX, Instrument.GUITAR, null);
        AudioJobResponse jobResp = AudioJobResponse.builder()
            .jobId(UUID.randomUUID().toString())
            .status(JobStatus.PENDING)
            .jobType(JobType.AI_GENERATE)
            .build();
        when(aiGenerateService.submit(req, "user2")).thenReturn(jobResp);

        ResponseEntity<?> response = controller.generate(req, "user2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("getJob - found - returns 200")
    void getJob_found_returns200() {
        String jobId = UUID.randomUUID().toString();
        AudioJobResponse jobResp = AudioJobResponse.builder()
            .jobId(jobId).status(JobStatus.PROCESSING).build();
        when(aiGenerateService.getJob(jobId, "user1")).thenReturn(jobResp);

        ResponseEntity<?> response = controller.getJob(jobId, "user1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("getJob - not found - throws AudioJobNotFoundException")
    void getJob_notFound_throws() {
        String jobId = UUID.randomUUID().toString();
        when(aiGenerateService.getJob(jobId, "user1"))
            .thenThrow(new AudioJobNotFoundException(jobId));

        assertThatThrownBy(() -> controller.getJob(jobId, "user1"))
            .isInstanceOf(AudioJobNotFoundException.class);
    }
}
