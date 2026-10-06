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
@RequestMapping("/api/v1/admin/market-calendar")
public class AdminMarketCalendarController {
    private final AdminMarketCalendarService service;

    public AdminMarketCalendarController(AdminMarketCalendarService service) { this.service = service; }

    @PostMapping
    public AdminMarketCalendarResponse create(@Valid @RequestBody AdminMarketCalendarRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<AdminMarketCalendarResponse> list() { return service.list(); }

    @PutMapping("/{id}")
    public AdminMarketCalendarResponse update(@PathVariable UUID id,
            @Valid @RequestBody AdminMarketCalendarRequest request) { return service.update(id, request); }

    @PostMapping("/{id}/deactivate")
    public AdminMarketCalendarResponse deactivate(@PathVariable UUID id) { return service.deactivate(id); }
}
