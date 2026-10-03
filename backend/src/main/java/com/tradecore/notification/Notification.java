package com.tradecore.notification;

import com.tradecore.identity.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification", indexes = @Index(name = "ix_notification_user_unread", columnList = "user_id,created_at"))
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private User user;
    @Column(name = "notification_type", nullable = false, length = 32) private String notificationType;
    @Column(nullable = false, length = 160) private String title;
    @Column(nullable = false, length = 1000) private String message;
    @Column(name = "read_at") private Instant readAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected Notification() {}
}
