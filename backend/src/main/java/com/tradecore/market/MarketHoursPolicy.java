package com.tradecore.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** NSE session policy with explicit calendar entries taking precedence over weekday defaults. */
@Component
public class MarketHoursPolicy {

    private final MarketDataRefreshProperties properties;
    private final ZoneId marketZone;
    private final MarketSessionRepository calendar;
    @Value("${tradecore.intraday.square-off-minutes-before-close:10}")
    private int squareOffMinutesBeforeClose = 10;

    @Autowired
    public MarketHoursPolicy(MarketDataRefreshProperties properties, MarketSessionRepository calendar) {
        this.properties = properties;
        this.marketZone = ZoneId.of(properties.getTimezone());
        this.calendar = calendar;
        if (!properties.getRegularSessionOpen().isBefore(properties.getRegularSessionClose())) {
            throw new IllegalArgumentException("Regular market open must be before regular market close");
        }
    }

    /** Preserves simple policy construction for isolated tests and tools without a repository. */
    public MarketHoursPolicy(MarketDataRefreshProperties properties) {
        this.properties = properties;
        this.marketZone = ZoneId.of(properties.getTimezone());
        this.calendar = null;
        if (!properties.getRegularSessionOpen().isBefore(properties.getRegularSessionClose())) {
            throw new IllegalArgumentException("Regular market open must be before regular market close");
        }
    }

    public boolean isRegularSession(Instant instant) {
        ZonedDateTime marketTime = instant.atZone(marketZone);
        Optional<SessionWindow> window = sessionWindow(marketTime.toLocalDate());
        if (window.isEmpty()) return false;
        LocalTime localTime = marketTime.toLocalTime();
        return !localTime.isBefore(window.get().opens()) && localTime.isBefore(window.get().closes());
    }

    public MarketSessionResponse sessionStatus(Instant instant) {
        ZonedDateTime marketTime = instant.atZone(marketZone);
        LocalDate date = marketTime.toLocalDate();
        MarketSession configured = calendar == null ? null : calendar.findByTradingDateAndActiveTrue(date).orElse(null);
        Optional<SessionWindow> window = sessionWindow(date);
        boolean open = window.map(value -> !marketTime.toLocalTime().isBefore(value.opens())
                && marketTime.toLocalTime().isBefore(value.closes())).orElse(false);
        boolean special = configured != null && configured.getOpensAt() != null && configured.getClosesAt() != null;
        String reason = open ? (special ? "SPECIAL_SESSION" : null)
                : special ? "SPECIAL_SESSION"
                : configured != null && (configured.isHoliday() || !"OPEN".equals(configured.getSessionState())) ? "HOLIDAY"
                : configured == null && (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY)
                        ? "WEEKEND" : "OUTSIDE_HOURS";
        Instant nextOpenAt = null;
        for (int offset = 0; offset <= 30; offset++) {
            LocalDate candidateDate = date.plusDays(offset);
            Optional<SessionWindow> candidate = sessionWindow(candidateDate);
            if (candidate.isEmpty()) continue;
            Instant candidateOpen = candidateDate.atTime(candidate.get().opens()).atZone(marketZone).toInstant();
            if (candidateOpen.isAfter(instant)) {
                nextOpenAt = candidateOpen;
                break;
            }
        }
        return new MarketSessionResponse(date, open ? "OPEN" : "CLOSED", reason,
                window.map(SessionWindow::opens).orElse(null), window.map(SessionWindow::closes).orElse(null), nextOpenAt);
    }

    /** True at or after the effective session close on a configured trading date. */
    public boolean isSessionEnded(Instant instant) {
        ZonedDateTime marketTime = instant.atZone(marketZone);
        Optional<SessionWindow> window = sessionWindow(marketTime.toLocalDate());
        return window.isPresent() && !marketTime.toLocalTime().isBefore(window.get().closes());
    }

    /** True during the configured pre-close window and for same-day post-close retries. */
    public boolean isSquareOffWindow(Instant instant) {
        ZonedDateTime marketTime = instant.atZone(marketZone);
        Optional<SessionWindow> window = sessionWindow(marketTime.toLocalDate());
        if (window.isEmpty()) return false;
        LocalTime localTime = marketTime.toLocalTime();
        LocalTime closes = window.get().closes();
        if (!localTime.isBefore(closes)) return true;
        return !localTime.isBefore(window.get().opens())
                && !localTime.isBefore(closes.minusMinutes(squareOffMinutesBeforeClose));
    }

    private Optional<SessionWindow> sessionWindow(LocalDate date) {
        MarketSession configured = null;
        if (calendar != null) {
            try {
                configured = calendar.findByTradingDateAndActiveTrue(date).orElse(null);
            } catch (RuntimeException failure) {
                return Optional.empty();
            }
        }
        if (configured != null) {
            if (!configured.isActive()) return Optional.empty();
            Instant opensAt = configured.getOpensAt();
            Instant closesAt = configured.getClosesAt();
            if ((opensAt == null) != (closesAt == null)) return Optional.empty();
            if (opensAt != null) {
                ZonedDateTime open = opensAt.atZone(marketZone);
                ZonedDateTime close = closesAt.atZone(marketZone);
                if (!open.toLocalDate().equals(date) || !close.toLocalDate().equals(date)
                        || !open.toLocalTime().isBefore(close.toLocalTime())) return Optional.empty();
                return Optional.of(new SessionWindow(open.toLocalTime(), close.toLocalTime()));
            }
            if (configured.isHoliday() || !"OPEN".equals(configured.getSessionState())) return Optional.empty();
        }
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return Optional.empty();
        return Optional.of(new SessionWindow(properties.getRegularSessionOpen(), properties.getRegularSessionClose()));
    }

    private record SessionWindow(LocalTime opens, LocalTime closes) { }
}
