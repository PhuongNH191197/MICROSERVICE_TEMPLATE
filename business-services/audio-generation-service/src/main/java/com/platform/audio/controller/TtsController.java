package com.platform.audio.controller;
import com.platform.audio.dto.response.TtsVoiceResponse;
import com.platform.audio.service.DiyService;
import com.platform.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/audio/tts")
@RequiredArgsConstructor
public class TtsController {
    private final DiyService diyService;

    @GetMapping("/voices")
    public ResponseEntity<ApiResponse<List<TtsVoiceResponse>>> voices() {
        return ResponseEntity.ok(ApiResponse.success(diyService.listVoices()));
    }
}
