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

    public Notification(User user, String notificationType, String title, String message, Instant createdAt) {
        this.user = user; this.notificationType = notificationType; this.title = title;
        this.message = message; this.createdAt = createdAt;
    }
    public UUID getId() { return id; }
    public User getUser() { return user; }
    public String getNotificationType() { return notificationType; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadAt() { return readAt; }
    public void markRead(Instant at) { if (readAt == null) readAt = at; }
}
