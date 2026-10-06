package com.tradecore.admin;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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

    public AdminRiskLimitController(AdminRiskLimitService service) { this.service = service; }

    @PostMapping
    public AdminRiskLimitResponse create(@Valid @RequestBody AdminRiskLimitRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<AdminRiskLimitResponse> list() { return service.list(); }

    @PutMapping("/{id}")
    public AdminRiskLimitResponse update(@PathVariable UUID id, @Valid @RequestBody AdminRiskLimitRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/activate")
    public AdminRiskLimitResponse activate(@PathVariable UUID id) { return service.activate(id); }

    @PostMapping("/{id}/deactivate")
    public AdminRiskLimitResponse deactivate(@PathVariable UUID id) { return service.deactivate(id); }
}
