package com.platform.audio.dto.response;
import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AnalyzeResponse {
    private boolean hasVocal;
    private Double bpm;
    private Long totalDurationMs;
    private List<SegmentInfo> segments;
}
