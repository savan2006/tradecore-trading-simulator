package com.tradecore.admin;

import com.tradecore.market.MarketDataRefreshProperties;
import com.tradecore.market.MarketSession;
import com.tradecore.market.MarketSessionRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminMarketCalendarService {
    private final MarketSessionRepository sessions;
    private final ZoneId zone;

    public AdminMarketCalendarService(MarketSessionRepository sessions, MarketDataRefreshProperties properties) {
        this.sessions = sessions;
        this.zone = ZoneId.of(properties.getTimezone());
    }

    @Transactional
    public AdminMarketCalendarResponse create(AdminMarketCalendarRequest request) {
        validate(request);
        if (sessions.findByTradingDate(request.tradingDate()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A calendar entry already exists for this date");
        }
        return view(sessions.saveAndFlush(new MarketSession(request.tradingDate(), request.holiday(),
                at(request.tradingDate(), request.sessionOpen()), at(request.tradingDate(), request.sessionClose()),
                cleanDescription(request.description()))));
    }

    @Transactional
    public AdminMarketCalendarResponse update(UUID id, AdminMarketCalendarRequest request) {
        validate(request);
        MarketSession entry = sessions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar entry was not found"));
        if (!entry.getTradingDate().equals(request.tradingDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tradingDate cannot be changed on an existing entry");
        }
        entry.update(request.holiday(), at(request.tradingDate(), request.sessionOpen()),
                at(request.tradingDate(), request.sessionClose()), cleanDescription(request.description()));
        return view(sessions.saveAndFlush(entry));
    }

    @Transactional(readOnly = true)
    public List<AdminMarketCalendarResponse> list() {
        return sessions.findAllByOrderByTradingDateDesc().stream().map(this::view).toList();
    }

    @Transactional
    public AdminMarketCalendarResponse deactivate(UUID id) {
        MarketSession entry = sessions.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar entry was not found"));
        ensureNotPast(entry.getTradingDate());
        entry.deactivate();
        return view(sessions.saveAndFlush(entry));
    }

    private void validate(AdminMarketCalendarRequest request) {
        if (request == null || request.tradingDate() == null || request.holiday() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tradingDate and holiday are required");
        }
        ensureNotPast(request.tradingDate());
        boolean hasOpen = request.sessionOpen() != null;
        boolean hasClose = request.sessionClose() != null;
        if (hasOpen != hasClose) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "sessionOpen and sessionClose must be supplied together");
        if (hasOpen && !request.sessionOpen().isBefore(request.sessionClose())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionOpen must be before sessionClose");
        }
    }

    private void ensureNotPast(LocalDate date) {
        if (date.isBefore(LocalDate.now(zone))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Past calendar dates cannot be changed");
        }
    }

    private Instant at(LocalDate date, LocalTime time) {
        return time == null ? null : date.atTime(time).atZone(zone).toInstant();
    }

    private AdminMarketCalendarResponse view(MarketSession entry) {
        LocalTime open = entry.getOpensAt() == null ? null : entry.getOpensAt().atZone(zone).toLocalTime();
        LocalTime close = entry.getClosesAt() == null ? null : entry.getClosesAt().atZone(zone).toLocalTime();
        return new AdminMarketCalendarResponse(entry.getId(), entry.getTradingDate(), entry.isHoliday(),
                open, close, entry.getDescription(), entry.isActive());
    }

    private static String cleanDescription(String description) {
        return description == null || description.isBlank() ? null : description.trim();
    }
}
