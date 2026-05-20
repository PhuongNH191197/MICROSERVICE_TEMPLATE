package com.platform.audio.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "credit_transactions")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CreditTransaction {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private Integer amount;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(name = "job_id")
    private UUID jobId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
