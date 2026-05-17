package com.platform.notification.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "notification_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    private String recipient;
    private String type;
    private String subject;
    @Enumerated(EnumType.STRING) @Builder.Default private Status status = Status.PENDING;
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @Column(name = "sent_at") private Instant sentAt;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;

    public enum Status { PENDING, SENT, FAILED }
}
