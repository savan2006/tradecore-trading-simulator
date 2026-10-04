package com.tradecore.order;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderHistoryController {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    private final OrderHistoryQueryService queryService;

    public OrderHistoryController(OrderHistoryQueryService queryService) { this.queryService = queryService; }

    @GetMapping
    public OrderHistoryPageResponse orders(Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String tradingMode,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.orders(authentication.getName(), status, symbol, tradingMode,
                parseDateBound(from, false), parseDateBound(to, true), page, size);
    }

    @GetMapping("/{orderId}")
    public OrderHistoryResponse order(Authentication authentication, @PathVariable UUID orderId) {
        return queryService.order(authentication.getName(), orderId);
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
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "from and to must be ISO timestamps or ISO dates");
            }
        }
    }
}
