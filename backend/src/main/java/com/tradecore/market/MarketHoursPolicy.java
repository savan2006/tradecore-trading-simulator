package com.tradecore.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** NSE session policy with explicit calendar entries taking precedence over weekday defaults. */
@Component
public class MarketHoursPolicy {

    private final MarketDataRefreshProperties properties;
    private final ZoneId marketZone;
    private final MarketSessionRepository calendar;

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

    /** True at or after the effective session close on a configured trading date. */
    public boolean isSessionEnded(Instant instant) {
        ZonedDateTime marketTime = instant.atZone(marketZone);
        Optional<SessionWindow> window = sessionWindow(marketTime.toLocalDate());
        return window.isPresent() && !marketTime.toLocalTime().isBefore(window.get().closes());
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
