package com.tradecore.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @Operation(summary = "List", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping
    public NotificationPageResponse list(Authentication authentication,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(authentication.getName(), unreadOnly, page, size);
    }

    @Operation(summary = "Unread Count", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(Authentication authentication) {
        return service.unreadCount(authentication.getName());
    }

    @Operation(summary = "Mark Read", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})

    @PostMapping("/{id}/read")
    public NotificationResponse markRead(Authentication authentication, @PathVariable UUID id) {
        return service.markRead(authentication.getName(), id);
    }

    @Operation(summary = "Mark All Read", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})

    @PostMapping("/read-all")
    public ReadAllResponse markAllRead(Authentication authentication) {
        return service.markAllRead(authentication.getName());
    }
}
