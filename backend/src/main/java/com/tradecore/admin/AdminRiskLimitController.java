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
@RequestMapping("/api/v1/admin/risk-limits")
public class AdminRiskLimitController {
    private final AdminRiskLimitService service;
    private final AuditService audit;

    public AdminRiskLimitController(AdminRiskLimitService service, AuditService audit) { this.service = service; this.audit = audit; }

    @PostMapping
    public AdminRiskLimitResponse create(@Valid @RequestBody AdminRiskLimitRequest request, Authentication authentication) {
        return audited(authentication, "ADMIN_RISK_LIMIT_CREATE", null, () -> service.create(request));
    }

    @GetMapping
    public List<AdminRiskLimitResponse> list() { return service.list(); }

    @PutMapping("/{id}")
    public AdminRiskLimitResponse update(@PathVariable UUID id, @Valid @RequestBody AdminRiskLimitRequest request,
            Authentication authentication) {
        return audited(authentication, "ADMIN_RISK_LIMIT_UPDATE", id, () -> service.update(id, request));
    }

    @PostMapping("/{id}/activate")
    public AdminRiskLimitResponse activate(@PathVariable UUID id, Authentication authentication) {
        return audited(authentication, "ADMIN_RISK_LIMIT_ACTIVATE", id, () -> service.activate(id));
    }

    @PostMapping("/{id}/deactivate")
    public AdminRiskLimitResponse deactivate(@PathVariable UUID id, Authentication authentication) {
        return audited(authentication, "ADMIN_RISK_LIMIT_DEACTIVATE", id, () -> service.deactivate(id));
    }

    private AdminRiskLimitResponse audited(Authentication authentication, String action, UUID id,
            Supplier<AdminRiskLimitResponse> operation) {
        try {
            AdminRiskLimitResponse response = operation.get();
            audit.recordOutcome(authentication.getName(), action, "RISK_LIMIT", id == null ? response.id() : id, "SUCCESS");
            return response;
        } catch (RuntimeException failure) {
            audit.recordOutcome(authentication.getName(), action, "RISK_LIMIT", id, "FAILURE");
            throw failure;
        }
    }
}
