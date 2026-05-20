package com.platform.audio.service;
import com.platform.audio.dto.request.AiGenerateRequest;
import com.platform.audio.dto.response.AudioJobResponse;
import com.platform.audio.entity.AudioJob;
import com.platform.audio.enums.*;
import com.platform.audio.exception.InsufficientCreditException;
import com.platform.audio.repository.AudioJobRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiGenerateServiceTest {
    @Mock AudioJobRepository jobRepo;
    @Mock CreditService creditService;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks AiGenerateService service;

    @Test
    @DisplayName("submit with cache HIT returns COMPLETED immediately")
    void submit_cacheHit_returnsCompleted() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("http://minio/audio/test.mp3");
        AudioJob savedJob = AudioJob.builder()
            .id(UUID.randomUUID()).status(JobStatus.COMPLETED)
            .jobType(JobType.AI_GENERATE).userId("user1").build();
        when(jobRepo.save(any())).thenReturn(savedJob);

        AiGenerateRequest req = new AiGenerateRequest(Genre.POP, Mood.HAPPY, Instrument.PIANO, null);
        AudioJobResponse resp = service.submit(req, "user1");

        assertThat(resp.getStatus()).isEqualTo(JobStatus.COMPLETED);
        verify(creditService).deductCredit("user1", null);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("submit with cache MISS creates PENDING job and queues it")
    void submit_cacheMiss_createsPendingJob() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        AudioJob savedJob = AudioJob.builder()
            .id(UUID.randomUUID()).status(JobStatus.PENDING)
            .jobType(JobType.AI_GENERATE).userId("user1").build();
        when(jobRepo.save(any())).thenReturn(savedJob);

        AiGenerateRequest req = new AiGenerateRequest(Genre.POP, Mood.HAPPY, Instrument.PIANO, null);
        AudioJobResponse resp = service.submit(req, "user1");

        assertThat(resp.getStatus()).isEqualTo(JobStatus.PENDING);
        verify(creditService).deductCredit("user1", null);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("submit with insufficient credit throws InsufficientCreditException")
    void submit_insufficientCredit_throws() {
        doThrow(new InsufficientCreditException("Insufficient credits")).when(creditService).deductCredit(any(), any());

        AiGenerateRequest req = new AiGenerateRequest(Genre.POP, Mood.HAPPY, Instrument.PIANO, null);
        assertThatThrownBy(() -> service.submit(req, "user1"))
            .isInstanceOf(InsufficientCreditException.class);
        verifyNoInteractions(rabbitTemplate);
    }
}
