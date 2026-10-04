package com.tradecore.watchlist;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/watchlists")
public class WatchlistController {
    private final WatchlistService service;
    public WatchlistController(WatchlistService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WatchlistResponse create(Authentication auth, @Valid @RequestBody WatchlistRequest request) {
        return service.create(auth.getName(), request);
    }
    @GetMapping
    public List<WatchlistResponse> list(Authentication auth) { return service.list(auth.getName()); }
    @GetMapping("/{id}")
    public WatchlistResponse get(Authentication auth, @PathVariable UUID id) { return service.get(auth.getName(), id); }
    @PutMapping("/{id}")
    public WatchlistResponse rename(Authentication auth, @PathVariable UUID id,
            @Valid @RequestBody WatchlistRequest request) { return service.rename(auth.getName(), id, request); }
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable UUID id) { service.delete(auth.getName(), id); }
    @PostMapping("/{id}/items")
    public WatchlistResponse add(Authentication auth, @PathVariable UUID id,
            @Valid @RequestBody WatchlistItemRequest request) { return service.add(auth.getName(), id, request); }
    @DeleteMapping("/{id}/items/{symbol}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication auth, @PathVariable UUID id, @PathVariable String symbol) {
        service.remove(auth.getName(), id, symbol);
    }
}
