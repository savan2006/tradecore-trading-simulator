package com.tradecore.market;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Component;

/** Regular NSE weekday session check; it deliberately does not infer holidays or special sessions. */
@Component
public class MarketHoursPolicy {

    private final MarketDataRefreshProperties properties;
    private final ZoneId marketZone;

    public MarketHoursPolicy(MarketDataRefreshProperties properties) {
        this.properties = properties;
        this.marketZone = ZoneId.of(properties.getTimezone());
        if (!properties.getRegularSessionOpen().isBefore(properties.getRegularSessionClose())) {
            throw new IllegalArgumentException("Regular market open must be before regular market close");
        }
    }

    public boolean isRegularSession(Instant instant) {
        ZonedDateTime marketTime = instant.atZone(marketZone);
        DayOfWeek day = marketTime.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        var localTime = marketTime.toLocalTime();
        return !localTime.isBefore(properties.getRegularSessionOpen())
                && localTime.isBefore(properties.getRegularSessionClose());
    }

    /** True on weekdays at or after the configured regular-session close. */
    public boolean isSessionEnded(Instant instant) {
        ZonedDateTime marketTime = instant.atZone(marketZone);
        DayOfWeek day = marketTime.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
                && !marketTime.toLocalTime().isBefore(properties.getRegularSessionClose());
    }
}
