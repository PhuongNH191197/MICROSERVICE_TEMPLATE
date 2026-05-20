package com.platform.audio.dto.response;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TtsVoiceResponse {
    private String voiceId;
    private String name;
    private String sampleUrl;
    private String language;
}
