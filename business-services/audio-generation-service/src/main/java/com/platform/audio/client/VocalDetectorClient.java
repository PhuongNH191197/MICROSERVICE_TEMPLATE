package com.platform.audio.client;
import com.platform.audio.dto.response.AnalyzeResponse;
import com.platform.audio.dto.response.SegmentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Component @Slf4j
public class VocalDetectorClient {
    private final WebClient webClient;

    public VocalDetectorClient(@Qualifier("vocalDetectorWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    @SuppressWarnings("unchecked")
    public AnalyzeResponse analyze(String fileKey) {
        log.info("Calling vocal-detector /analyze fileKey={}", fileKey);
        try {
            Map<?, ?> result = webClient.post().uri("/analyze")
                .bodyValue(Map.of("fileKey", fileKey))
                .retrieve()
                .bodyToMono(Map.class)
                .block(Duration.ofSeconds(30));

            if (result == null) throw new RuntimeException("vocal-detector returned null");

            boolean hasVocal = Boolean.TRUE.equals(result.get("hasVocal"));
            Double bpm = result.get("bpm") instanceof Number n ? n.doubleValue() : 120.0;
            Long totalMs = result.get("totalDurationMs") instanceof Number n ? n.longValue() : 0L;

            List<Map<String, Object>> rawSegs = (List<Map<String, Object>>) result.get("segments");
            List<SegmentInfo> segments = rawSegs != null
                ? rawSegs.stream().map(s -> SegmentInfo.builder()
                    .startMs(((Number) s.get("startMs")).intValue())
                    .endMs(((Number) s.get("endMs")).intValue())
                    .label((String) s.get("label"))
                    .build()).collect(Collectors.toList())
                : List.of();

            return AnalyzeResponse.builder()
                .hasVocal(hasVocal).bpm(bpm).totalDurationMs(totalMs).segments(segments).build();
        } catch (Exception e) {
            log.warn("vocal-detector call failed, returning fallback: {}", e.getMessage());
            return AnalyzeResponse.builder()
                .hasVocal(false).bpm(120.0).totalDurationMs(180000L)
                .segments(List.of(
                    SegmentInfo.builder().startMs(0).endMs(50000).label("Segment 1").build(),
                    SegmentInfo.builder().startMs(30000).endMs(80000).label("Segment 2").build(),
                    SegmentInfo.builder().startMs(60000).endMs(110000).label("Segment 3").build()
                )).build();
        }
    }
}
