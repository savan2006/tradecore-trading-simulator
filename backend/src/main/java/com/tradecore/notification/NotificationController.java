package com.tradecore.notification;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping
    public NotificationPageResponse list(Authentication authentication,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(authentication.getName(), unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(Authentication authentication) {
        return service.unreadCount(authentication.getName());
    }

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(Authentication authentication, @PathVariable UUID id) {
        return service.markRead(authentication.getName(), id);
    }

    @PostMapping("/read-all")
    public ReadAllResponse markAllRead(Authentication authentication) {
        return service.markAllRead(authentication.getName());
    }
}
