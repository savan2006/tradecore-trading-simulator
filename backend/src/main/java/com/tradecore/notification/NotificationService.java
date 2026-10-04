package com.tradecore.notification;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class NotificationService {
    private static final int MAX_PAGE_SIZE = 100;
    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) { this.notifications = notifications; }

    public NotificationPageResponse list(String email, boolean unreadOnly, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination parameters");
        }
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Notification> result = unreadOnly
                ? notifications.findByUser_EmailAndReadAtIsNullOrderByCreatedAtDescIdDesc(email, pageable)
                : notifications.findByUser_EmailOrderByCreatedAtDescIdDesc(email, pageable);
        return new NotificationPageResponse(result.getContent().stream().map(NotificationResponse::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public UnreadCountResponse unreadCount(String email) {
        return new UnreadCountResponse(notifications.countByUser_EmailAndReadAtIsNull(email));
    }

    @Transactional
    public NotificationResponse markRead(String email, UUID id) {
        notifications.markReadIfUnread(id, email, Instant.now());
        Notification notification = notifications.findByIdAndUser_Email(id, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found"));
        return NotificationResponse.from(notification);
    }

    @Transactional
    public ReadAllResponse markAllRead(String email) {
        return new ReadAllResponse(notifications.markAllRead(email, Instant.now()));
    }
}
