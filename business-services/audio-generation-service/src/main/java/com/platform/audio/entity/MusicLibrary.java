package com.platform.audio.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "music_library")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MusicLibrary {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String title;

    private String artist;
    private String genre;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "file_key", nullable = false, length = 500)
    private String fileKey;

    @Column(nullable = false, length = 100)
    @Builder.Default
    private String bucket = "media-audio";

    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
