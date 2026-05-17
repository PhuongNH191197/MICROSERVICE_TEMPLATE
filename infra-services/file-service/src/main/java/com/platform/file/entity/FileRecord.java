package com.platform.file.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "files")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FileRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "user_id") private String userId;
    @Column(name = "original_name") private String originalName;
    @Column(name = "stored_name") private String storedName;
    private String bucket;
    private Long size;
    @Column(name = "content_type") private String contentType;
    @Builder.Default private boolean publicFile = false;
    @Builder.Default private boolean deleted = false;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
