package com.tradecore.journal;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/v1/journal")
public class TradeJournalController {
    private final TradeJournalService service;

    public TradeJournalController(TradeJournalService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TradeJournalResponse create(Authentication authentication, @Valid @RequestBody TradeJournalRequest request) {
        return service.create(authentication.getName(), request);
    }

    @GetMapping
    public TradeJournalPageResponse list(Authentication authentication,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(authentication.getName(), page, size);
    }

    @GetMapping("/{id}")
    public TradeJournalResponse get(Authentication authentication, @PathVariable UUID id) {
        return service.get(authentication.getName(), id);
    }

    @PutMapping("/{id}")
    public TradeJournalResponse update(Authentication authentication, @PathVariable UUID id,
            @Valid @RequestBody TradeJournalUpdateRequest request) {
        return service.update(authentication.getName(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable UUID id) {
        service.delete(authentication.getName(), id);
    }
}
