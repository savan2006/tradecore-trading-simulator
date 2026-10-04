package com.tradecore.alert;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class PriceAlertController {
    private final PriceAlertService service;
    public PriceAlertController(PriceAlertService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PriceAlertResponse create(Authentication auth, @Valid @RequestBody PriceAlertRequest request) {
        return service.create(auth.getName(), request);
    }
    @GetMapping
    public List<PriceAlertResponse> list(Authentication auth) { return service.list(auth.getName()); }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID id) { service.delete(auth.getName(), id); }
}
