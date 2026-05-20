package com.platform.audio.service;
import com.platform.audio.client.ElevenLabsClient;
import com.platform.audio.client.VocalDetectorClient;
import com.platform.audio.config.RabbitMQConfig;
import com.platform.audio.dto.request.DiyAnalyzeRequest;
import com.platform.audio.dto.request.DiyGenerateRequest;
import com.platform.audio.dto.response.*;
import com.platform.audio.entity.AudioJob;
import com.platform.audio.entity.MusicLibrary;
import com.platform.audio.enums.JobStatus;
import com.platform.audio.enums.JobType;
import com.platform.audio.exception.JobLimitExceededException;
import com.platform.audio.exception.VocalDetectedException;
import com.platform.audio.repository.AudioJobRepository;
import com.platform.audio.repository.MusicLibraryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service @RequiredArgsConstructor @Slf4j
public class DiyService {
    private final VocalDetectorClient vocalDetectorClient;
    private final ElevenLabsClient elevenLabsClient;
    private final AudioJobRepository jobRepo;
    private final MusicLibraryRepository musicLibraryRepo;
    private final CreditService creditService;
    private final RabbitTemplate rabbitTemplate;
    private final AiGenerateService aiGenerateService;

    public AnalyzeResponse analyze(DiyAnalyzeRequest req, String userId) {
        AnalyzeResponse result = vocalDetectorClient.analyze(req.getFileKey());
        if (result.isHasVocal()) {
            throw new VocalDetectedException(
                "Music contains vocals. Please use an instrumental track.");
        }
        return result;
    }

    @Transactional
    public AudioJobResponse submitDiy(DiyGenerateRequest req, String userId) {
        long active = jobRepo.countByUserIdAndStatusIn(userId,
            List.of(JobStatus.PENDING, JobStatus.PROCESSING));
        if (active >= 5)
            throw new JobLimitExceededException("Max 5 concurrent jobs per user");

        creditService.deductCredit(userId, null);

        String title = req.getTitle() != null ? req.getTitle()
            : req.getText().substring(0, Math.min(80, req.getText().length()));

        AudioJob job = AudioJob.builder()
            .userId(userId).jobType(JobType.DIY_MIX).status(JobStatus.PENDING)
            .sourceFileKey(req.getSourceFileKey())
            .segmentStartMs(req.getSegmentStartMs())
            .segmentEndMs(req.getSegmentEndMs())
            .ttsText(req.getText())
            .voiceId(req.getVoiceId())
            .title(title)
            .format("MP3").build();
        job = jobRepo.save(job);

        rabbitTemplate.convertAndSend(RabbitMQConfig.AUDIO_EXCHANGE,
            RabbitMQConfig.ROUTING_DIY, job.getId().toString());
        log.info("Queued DIY job id={} userId={}", job.getId(), userId);
        return aiGenerateService.toResponse(job);
    }

    public Page<MusicLibrary> listLibrary(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        if (query == null || query.isBlank())
            return musicLibraryRepo.findByActiveTrueOrderByCreatedAtDesc(pageable);
        return musicLibraryRepo.searchByText(query, pageable);
    }

    public List<TtsVoiceResponse> listVoices() {
        return elevenLabsClient.listVoices();
    }
}
