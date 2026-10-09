package com.tradecore.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MarketHoursPolicyTest {
    private static final ZoneId NSE = ZoneId.of("Asia/Kolkata");

    @Test
    void usesWeekdayDefaultsAndClosesWeekends() {
        MarketHoursPolicy policy = new MarketHoursPolicy(new MarketDataRefreshProperties(), mock(MarketSessionRepository.class));
        assertThat(policy.isRegularSession(at("2026-10-05", "09:15"))).isTrue();
        assertThat(policy.isRegularSession(at("2026-10-05", "15:30"))).isFalse();
        assertThat(policy.isSquareOffWindow(at("2026-10-05", "15:19:59"))).isFalse();
        assertThat(policy.isSquareOffWindow(at("2026-10-05", "15:20"))).isTrue();
        assertThat(policy.isSquareOffWindow(at("2026-10-05", "15:29"))).isTrue();
        assertThat(policy.isSquareOffWindow(at("2026-10-05", "15:30"))).isTrue();
        assertThat(policy.isRegularSession(at("2026-10-03", "10:00"))).isFalse();
        assertThat(policy.isSessionEnded(at("2026-10-03", "16:00"))).isFalse();
        assertThat(policy.isSquareOffWindow(at("2026-10-03", "17:00"))).isFalse();
    }

    @Test
    void holidayClosesSessionAndSessionOverrideReplacesDefaultTimes() {
        MarketSessionRepository calendar = mock(MarketSessionRepository.class);
        LocalDate holidayDate = LocalDate.parse("2027-01-01");
        LocalDate overrideDate = LocalDate.parse("2027-01-04");
        when(calendar.findByTradingDateAndActiveTrue(holidayDate)).thenReturn(Optional.of(
                new MarketSession(holidayDate, true, null, null, "configured holiday")));
        when(calendar.findByTradingDateAndActiveTrue(overrideDate)).thenReturn(Optional.of(
                new MarketSession(overrideDate, false, at(overrideDate, LocalTime.of(10, 0)),
                        at(overrideDate, LocalTime.of(13, 0)), "configured session")));
        MarketHoursPolicy policy = new MarketHoursPolicy(new MarketDataRefreshProperties(), calendar);

        assertThat(policy.isRegularSession(at("2027-01-01", "10:00"))).isFalse();
        assertThat(policy.isSessionEnded(at("2027-01-01", "16:00"))).isFalse();
        assertThat(policy.isRegularSession(at("2027-01-04", "09:59"))).isFalse();
        assertThat(policy.isRegularSession(at("2027-01-04", "10:00"))).isTrue();
        assertThat(policy.isRegularSession(at("2027-01-04", "12:59"))).isTrue();
        assertThat(policy.isRegularSession(at("2027-01-04", "13:00"))).isFalse();
        assertThat(policy.isSquareOffWindow(at("2027-01-04", "12:50"))).isTrue();
        assertThat(policy.isSquareOffWindow(at("2027-01-04", "13:00"))).isTrue();
        assertThat(policy.isSquareOffWindow(at("2027-01-01", "16:00"))).isFalse();
        assertThat(policy.isSessionEnded(at("2027-01-04", "12:59"))).isFalse();
        assertThat(policy.isSessionEnded(at("2027-01-04", "13:00"))).isTrue();
    }

    @Test
    void explicitWeekendSpecialSessionOverridesWeekendAndHolidayDefaults() {
        MarketSessionRepository calendar = mock(MarketSessionRepository.class);
        LocalDate date = LocalDate.parse("2026-11-08");
        when(calendar.findByTradingDateAndActiveTrue(date)).thenReturn(Optional.of(
                new MarketSession(date, true, at(date, LocalTime.of(17, 0)),
                        at(date, LocalTime.of(18, 0)), "special session")));
        MarketHoursPolicy policy = new MarketHoursPolicy(new MarketDataRefreshProperties(), calendar);

        assertThat(policy.isRegularSession(at("2026-11-08", "16:59"))).isFalse();
        assertThat(policy.isRegularSession(at("2026-11-08", "17:00"))).isTrue();
        assertThat(policy.isRegularSession(at("2026-11-08", "17:59"))).isTrue();
        assertThat(policy.isRegularSession(at("2026-11-08", "18:00"))).isFalse();
        assertThat(policy.isSessionEnded(at("2026-11-08", "18:00"))).isTrue();
        assertThat(policy.isSquareOffWindow(at("2026-11-08", "17:50"))).isTrue();
        assertThat(policy.isSquareOffWindow(at("2026-11-08", "18:00"))).isTrue();
    }

    @Test
    void invalidOverrideFailsClosed() {
        MarketSessionRepository calendar = mock(MarketSessionRepository.class);
        LocalDate date = LocalDate.parse("2027-01-04");
        when(calendar.findByTradingDateAndActiveTrue(date)).thenReturn(Optional.of(
                new MarketSession(date, false, at(date, LocalTime.of(14, 0)), at(date, LocalTime.of(10, 0)), null)));
        MarketHoursPolicy policy = new MarketHoursPolicy(new MarketDataRefreshProperties(), calendar);
        assertThat(policy.isRegularSession(at("2027-01-04", "11:00"))).isFalse();
        assertThat(policy.isSessionEnded(at("2027-01-04", "16:00"))).isFalse();
    }

    private static Instant at(String date, String time) {
        return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(NSE).toInstant();
    }
    private static Instant at(LocalDate date, LocalTime time) { return date.atTime(time).atZone(NSE).toInstant(); }
}
