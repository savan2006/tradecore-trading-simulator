package com.tradecore.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

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

@Tag(name = "Admin")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/admin/market-calendar")
public class AdminMarketCalendarController {
    private final AdminMarketCalendarService service;
    private final AuditService audit;

    public AdminMarketCalendarController(AdminMarketCalendarService service, AuditService audit) { this.service = service; this.audit = audit; }

    @Operation(summary = "Create", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})

    @PostMapping
    public AdminMarketCalendarResponse create(@Valid @RequestBody AdminMarketCalendarRequest request,
            Authentication authentication) {
        return audited(authentication, "ADMIN_MARKET_CALENDAR_CREATE", null, () -> service.create(request));
    }

    @Operation(summary = "List", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required")})

    @GetMapping
    public List<AdminMarketCalendarResponse> list() { return service.list(); }

    @Operation(summary = "Update", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})

    @PutMapping("/{id}")
    public AdminMarketCalendarResponse update(@PathVariable UUID id,
            @Valid @RequestBody AdminMarketCalendarRequest request, Authentication authentication) {
        return audited(authentication, "ADMIN_MARKET_CALENDAR_UPDATE", id, () -> service.update(id, request));
    }

    @Operation(summary = "Deactivate", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})

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
