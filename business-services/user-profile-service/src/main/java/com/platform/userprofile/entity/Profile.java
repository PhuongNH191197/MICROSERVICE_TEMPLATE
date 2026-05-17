package com.platform.userprofile.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;

@Entity @Table(name = "profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Profile {
    @Id private String id;
    @Column(name = "full_name") private String fullName;
    @Column(name = "avatar_url") private String avatarUrl;
    @Column(length = 1000) private String bio;
    private String phone;
    private String address;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
