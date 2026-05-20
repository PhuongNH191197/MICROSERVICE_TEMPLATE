package com.platform.audio.controller;
import com.platform.audio.dto.request.DiyAnalyzeRequest;
import com.platform.audio.dto.request.DiyGenerateRequest;
import com.platform.audio.dto.response.*;
import com.platform.audio.entity.MusicLibrary;
import com.platform.audio.service.DiyService;
import com.platform.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audio/diy")
@RequiredArgsConstructor @Slf4j
public class DiyController {
    private final DiyService diyService;

    @PostMapping("/analyze")
    public ResponseEntity<ApiResponse<AnalyzeResponse>> analyze(
            @Valid @RequestBody DiyAnalyzeRequest req,
            @RequestHeader("X-User-Id") String userId) {
        log.info("DIY analyze request userId={} fileKey={}", userId, req.getFileKey());
        return ResponseEntity.ok(ApiResponse.success(diyService.analyze(req, userId)));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<AudioJobResponse>> generate(
            @Valid @RequestBody DiyGenerateRequest req,
            @RequestHeader("X-User-Id") String userId) {
        log.info("DIY generate request userId={}", userId);
        AudioJobResponse result = diyService.submitDiy(req, userId);
        return ResponseEntity.accepted().body(ApiResponse.success(result));
    }

    @GetMapping("/library")
    public ResponseEntity<ApiResponse<Page<MusicLibrary>>> library(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(diyService.listLibrary(q, page, size)));
    }
}
