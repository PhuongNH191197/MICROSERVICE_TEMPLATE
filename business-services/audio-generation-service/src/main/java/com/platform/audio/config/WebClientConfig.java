package com.platform.audio.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {
    @Bean("elevenLabsWebClient")
    public WebClient elevenLabsWebClient(
            @Value("${elevenlabs.base-url:https://api.elevenlabs.io}") String baseUrl,
            @Value("${elevenlabs.api-key:}") String apiKey) {
        return WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("xi-api-key", apiKey)
            .build();
    }

    @Bean("vocalDetectorWebClient")
    public WebClient vocalDetectorWebClient(
            @Value("${vocal-detector.url:http://vocal-detector:8765}") String url) {
        return WebClient.builder().baseUrl(url).build();
    }
}
