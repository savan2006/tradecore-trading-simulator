package com.tradecore.notification;

import com.tradecore.identity.User;
import com.tradecore.identity.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class NotificationService {
    private static final int MAX_PAGE_SIZE = 100;
    private final NotificationRepository notifications;
    private final UserRepository users;

    public NotificationService(NotificationRepository notifications, UserRepository users) {
        this.notifications = notifications;
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIfAbsent(String email, String type, String title, String message) {
        User user = users.findByEmail(email).orElse(null);
        if (user == null || notifications.existsByUser_IdAndNotificationTypeAndTitle(user.getId(), type, title)) return;
        notifications.save(new Notification(user, type, title, message, Instant.now()));
    }

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
