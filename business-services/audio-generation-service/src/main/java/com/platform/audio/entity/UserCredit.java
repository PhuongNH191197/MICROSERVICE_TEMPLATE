package com.platform.audio.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "user_credits")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class UserCredit {
    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(nullable = false)
    @Builder.Default
    private Integer balance = 0;

    @Column(name = "updated_at")
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
