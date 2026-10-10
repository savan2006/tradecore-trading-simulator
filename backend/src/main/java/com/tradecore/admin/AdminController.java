package com.tradecore.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import com.tradecore.audit.AuditService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private final AdminOperationsService service;
    private final AdminAuditLogService auditLogService;
    private final AuditService audit;
    private final ReconciliationService reconciliation;

    public AdminController(AdminOperationsService service, AdminAuditLogService auditLogService, AuditService audit,
            ReconciliationService reconciliation) {
        this.service = service;
        this.auditLogService = auditLogService;
        this.audit = audit;
        this.reconciliation = reconciliation;
    }

    @Operation(summary = "Overview", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required")})

    @GetMapping("/overview")
    AdminOverviewResponse overview(Authentication authentication) {
        recordAccess(authentication, "ADMIN_ACCESS_OVERVIEW", "/api/v1/admin/overview");
        return service.overview();
    }

    @Operation(summary = "Users", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required")})

    @GetMapping("/users")
    AdminUserPageResponse users(@RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication authentication) {
        recordAccess(authentication, "ADMIN_ACCESS_USERS", "/api/v1/admin/users");
        return service.users(search, page, size);
    }

    @Operation(summary = "Orders", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required")})

    @GetMapping("/orders")
    AdminOrderPageResponse orders(@RequestParam(required = false) String status,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String tradingMode,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication authentication) {
        recordAccess(authentication, "ADMIN_ACCESS_ORDERS", "/api/v1/admin/orders");
        return service.orders(status, symbol, tradingMode, parseDateBound(from, false),
                parseDateBound(to, true), page, size);
    }

    @Operation(summary = "Market Status", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required")})

    @GetMapping("/market-status")
    AdminMarketStatusResponse marketStatus(Authentication authentication) {
        recordAccess(authentication, "ADMIN_ACCESS_MARKET_STATUS", "/api/v1/admin/market-status");
        return service.marketStatus();
    }

    /** H2 tests do not prove PostgreSQL row-lock behavior; run this check against the real database after live testing. */
    @Operation(summary = "Reconciliation", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required")})
    @GetMapping("/reconciliation")
    java.util.List<ReconciliationService.Check> reconciliation() {
        return reconciliation.reconcile();
    }

    @Operation(summary = "Audit Logs", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required")})

    @GetMapping("/audit-logs")
    AdminAuditLogPageResponse auditLogs(@RequestParam(required = false) String action,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication authentication) {
        recordAccess(authentication, "ADMIN_ACCESS_AUDIT_LOGS", "/api/v1/admin/audit-logs");
        return auditLogService.search(action, actor, targetType, outcome,
                parseDateBound(from, false), parseDateBound(to, true), page, size);
    }

    private void recordAccess(Authentication authentication, String action, String endpoint) {
        audit.record(authentication.getName(), action, "ADMIN_API", null,
                "{\"endpoint\":\"" + endpoint + "\"}");
    }

    private static Instant parseDateBound(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException notAnInstant) {
            try {
                LocalDate date = LocalDate.parse(value);
                return date.atTime(endOfDay ? LocalTime.MAX : LocalTime.MIN).atZone(MARKET_ZONE).toInstant();
            } catch (DateTimeParseException notADate) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "from and to must be ISO timestamps or ISO dates");
            }
        }
    }
}
