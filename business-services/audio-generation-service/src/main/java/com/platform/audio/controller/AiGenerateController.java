package com.platform.audio.controller;
import com.platform.audio.dto.request.AiGenerateRequest;
import com.platform.audio.dto.response.AudioJobResponse;
import com.platform.audio.enums.JobStatus;
import com.platform.audio.service.AiGenerateService;
import com.platform.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audio/ai")
@RequiredArgsConstructor @Slf4j
public class AiGenerateController {
    private final AiGenerateService aiGenerateService;

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<AudioJobResponse>> generate(
            @Valid @RequestBody AiGenerateRequest req,
            @RequestHeader("X-User-Id") String userId) {
        log.info("AI generate request userId={} genre={} mood={} instrument={}",
            userId, req.getGenre(), req.getMood(), req.getInstrument());
        AudioJobResponse result = aiGenerateService.submit(req, userId);
        if (result.getStatus() == JobStatus.COMPLETED) {
            return ResponseEntity.ok(ApiResponse.success(result));
        }
        return ResponseEntity.accepted().body(ApiResponse.success(result));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ApiResponse<AudioJobResponse>> getJob(
            @PathVariable String jobId,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(ApiResponse.success(aiGenerateService.getJob(jobId, userId)));
    }
}
