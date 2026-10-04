package com.tradecore.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID id, String notificationType, String title, String message,
                                   Instant createdAt, Instant readAt) {
    static NotificationResponse from(Notification notification) {
        return new NotificationResponse(notification.getId(), notification.getNotificationType(),
                notification.getTitle(), notification.getMessage(), notification.getCreatedAt(), notification.getReadAt());
    }
}
