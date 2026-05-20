package com.platform.audio.dto.response;
import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SegmentInfo {
    private Integer startMs;
    private Integer endMs;
    private String label;
}
