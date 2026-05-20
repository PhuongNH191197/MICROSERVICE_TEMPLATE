package com.platform.audio.dto.request;
import com.platform.audio.enums.Genre;
import com.platform.audio.enums.Instrument;
import com.platform.audio.enums.Mood;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class AiGenerateRequest {
    @NotNull private Genre genre;
    @NotNull private Mood mood;
    @NotNull private Instrument instrument;
    private String title;
}
