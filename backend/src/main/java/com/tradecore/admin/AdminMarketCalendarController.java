package com.tradecore.admin;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import com.tradecore.audit.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/market-calendar")
public class AdminMarketCalendarController {
    private final AdminMarketCalendarService service;
    private final AuditService audit;

    public AdminMarketCalendarController(AdminMarketCalendarService service, AuditService audit) { this.service = service; this.audit = audit; }

    @PostMapping
    public AdminMarketCalendarResponse create(@Valid @RequestBody AdminMarketCalendarRequest request,
            Authentication authentication) {
        return audited(authentication, "ADMIN_MARKET_CALENDAR_CREATE", null, () -> service.create(request));
    }

    @GetMapping
    public List<AdminMarketCalendarResponse> list() { return service.list(); }

    @PutMapping("/{id}")
    public AdminMarketCalendarResponse update(@PathVariable UUID id,
            @Valid @RequestBody AdminMarketCalendarRequest request, Authentication authentication) {
        return audited(authentication, "ADMIN_MARKET_CALENDAR_UPDATE", id, () -> service.update(id, request));
    }

    @PostMapping("/{id}/deactivate")
    public AdminMarketCalendarResponse deactivate(@PathVariable UUID id, Authentication authentication) {
        return audited(authentication, "ADMIN_MARKET_CALENDAR_DEACTIVATE", id, () -> service.deactivate(id));
    }

    private AdminMarketCalendarResponse audited(Authentication authentication, String action, UUID id,
            Supplier<AdminMarketCalendarResponse> operation) {
        try {
            AdminMarketCalendarResponse response = operation.get();
            audit.recordOutcome(authentication.getName(), action, "MARKET_CALENDAR",
                    id == null ? response.id() : id, "SUCCESS");
            return response;
        } catch (RuntimeException failure) {
            audit.recordOutcome(authentication.getName(), action, "MARKET_CALENDAR", id, "FAILURE");
            throw failure;
        }
    }
}
