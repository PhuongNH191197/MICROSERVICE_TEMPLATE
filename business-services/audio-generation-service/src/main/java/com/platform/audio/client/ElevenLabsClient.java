package com.platform.audio.client;
import com.platform.audio.dto.response.TtsVoiceResponse;
import com.platform.audio.exception.AudioProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component @Slf4j
public class ElevenLabsClient {
    private final WebClient webClient;

    public ElevenLabsClient(@Qualifier("elevenLabsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public byte[] synthesize(String text, String voiceId) {
        log.info("TTS synthesis voiceId={} textLen={}", voiceId, text.length());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        body.put("model_id", "eleven_multilingual_v2");
        body.put("voice_settings", Map.of("stability", 0.5, "similarity_boost", 0.75));

        byte[] result = webClient.post()
            .uri("/v1/text-to-speech/" + voiceId)
            .header("Accept", "audio/mpeg")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(byte[].class)
            .block(Duration.ofSeconds(30));

        if (result == null) throw new AudioProcessingException("ElevenLabs returned null audio");
        return result;
    }

    @SuppressWarnings("unchecked")
    public List<TtsVoiceResponse> listVoices() {
        try {
            Map<?, ?> resp = webClient.get().uri("/v1/voices")
                .retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(10));
            if (resp == null) return getDefaultVoices();
            List<Map<String, Object>> voices = (List<Map<String, Object>>) resp.get("voices");
            if (voices == null) return getDefaultVoices();
            return voices.stream().map(v -> TtsVoiceResponse.builder()
                .voiceId((String) v.get("voice_id"))
                .name((String) v.get("name"))
                .sampleUrl((String) v.get("preview_url"))
                .build()).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to fetch ElevenLabs voices: {}", e.getMessage());
            return getDefaultVoices();
        }
    }

    private List<TtsVoiceResponse> getDefaultVoices() {
        return List.of(
            TtsVoiceResponse.builder().voiceId("21m00Tcm4TlvDq8ikWAM").name("Rachel").language("en").build(),
            TtsVoiceResponse.builder().voiceId("AZnzlk1XvdvUeBnXmlld").name("Domi").language("en").build()
        );
    }
}
