package com.platform.audio.client;
import com.platform.audio.exception.AudioProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Component @Slf4j
public class GeminiLyriaClient {
    private static final String LYRIA_MODEL = "lyria-3-clip-preview";

    @Value("${gemini.api-key:}") private String apiKey;
    @Value("${gemini.base-url:https://generativelanguage.googleapis.com}") private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public byte[] generateClip(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new AudioProcessingException("GEMINI_API_KEY not configured");
        }
        String url = baseUrl + "/v1beta/models/" + LYRIA_MODEL + ":generateContent";

        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt))))
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Goog-Api-Key", apiKey);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            log.info("Calling Gemini Lyria 3 model={}", LYRIA_MODEL);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
            Map respBody = response.getBody();
            List candidates = (List) respBody.get("candidates");
            Map candidate = (Map) candidates.get(0);
            Map responseContent = (Map) candidate.get("content");
            List parts = (List) responseContent.get("parts");
            Map part = (Map) parts.get(0);
            Map inlineData = (Map) part.get("inlineData");
            String base64Audio = (String) inlineData.get("data");
            return Base64.getDecoder().decode(base64Audio);
        } catch (AudioProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new AudioProcessingException("Gemini Lyria call failed: " + e.getMessage(), e);
        }
    }
}
