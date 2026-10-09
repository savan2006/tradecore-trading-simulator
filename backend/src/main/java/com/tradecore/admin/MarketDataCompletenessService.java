package com.tradecore.admin;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MarketDataCompletenessService {
    private static final ZoneId NSE_ZONE = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate jdbcTemplate;

    public MarketDataCompletenessService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public MarketDataCompletenessReport report(int months) {
        if (months < 1 || months > 36) {
            throw new IllegalArgumentException("months must be between 1 and 36");
        }
        LocalDate through = LocalDate.now(NSE_ZONE).minusDays(1);
        LocalDate from = through.minusMonths(months);
        Instant fromInstant = from.atStartOfDay(NSE_ZONE).toInstant();
        Instant toExclusive = through.plusDays(1).atStartOfDay(NSE_ZONE).toInstant();

        // One bounded bulk read returns candle rows and seeded holidays without provider calls.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT i.symbol AS symbol, c.bucket_start AS bucket_start, CAST(NULL AS DATE) AS holiday_date
                FROM instrument i
                LEFT JOIN market_candle c ON c.instrument_id = i.id AND c.resolution = '1D'
                    AND c.bucket_start >= ? AND c.bucket_start < ?
                WHERE i.exchange = 'NSE' AND i.tradable = TRUE
                UNION ALL
                SELECT CAST(NULL AS VARCHAR(32)) AS symbol,
                    CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS bucket_start, ms.trading_date AS holiday_date
                FROM market_session ms
                WHERE ms.active = TRUE AND ms.holiday = TRUE AND ms.trading_date >= ? AND ms.trading_date <= ?
                """, fromInstant, toExclusive, from, through);

        Map<String, Set<LocalDate>> candleDates = new HashMap<>();
        Set<LocalDate> holidays = new HashSet<>();
        for (Map<String, Object> row : rows) {
            String symbol = (String) row.get("symbol");
            if (symbol != null) {
                Set<LocalDate> dates = candleDates.computeIfAbsent(symbol, ignored -> new HashSet<>());
                Object bucket = row.get("bucket_start");
                if (bucket instanceof Timestamp timestamp) {
                    dates.add(timestamp.toInstant().atZone(NSE_ZONE).toLocalDate());
                } else if (bucket instanceof Instant instant) {
                    dates.add(instant.atZone(NSE_ZONE).toLocalDate());
                } else if (bucket instanceof OffsetDateTime offsetDateTime) {
                    dates.add(offsetDateTime.toInstant().atZone(NSE_ZONE).toLocalDate());
                }
            }
            Object holiday = row.get("holiday_date");
            if (holiday instanceof java.sql.Date date) {
                holidays.add(date.toLocalDate());
            } else if (holiday instanceof LocalDate date) {
                holidays.add(date);
            }
        }

        List<LocalDate> weekdays = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(through); date = date.plusDays(1)) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                weekdays.add(date);
            }
        }
        List<LocalDate> expected = weekdays.stream().filter(date -> !holidays.contains(date)).toList();
        List<MarketDataCompletenessReport.InstrumentCompleteness> results = new ArrayList<>();
        int withData = 0;
        int likelyComplete = 0;
        for (Map.Entry<String, Set<LocalDate>> entry : candleDates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            Set<LocalDate> dates = entry.getValue();
            List<LocalDate> missing = expected.stream().filter(date -> !dates.contains(date)).toList();
            LocalDate first = dates.stream().min(LocalDate::compareTo).orElse(null);
            LocalDate last = dates.stream().max(LocalDate::compareTo).orElse(null);
            if (!dates.isEmpty()) withData++;
            if (!dates.isEmpty() && missing.isEmpty()) likelyComplete++;
            results.add(new MarketDataCompletenessReport.InstrumentCompleteness(
                    entry.getKey(), dates.size(), first, last, weekdays.size(), expected.size(), missing.size(),
                    missing.stream().limit(20).toList(), last == null || last.isBefore(expected.getLast())));
        }
        int total = results.size();
        return new MarketDataCompletenessReport(months,
                "Weekdays are checked against active seeded market-calendar holidays; holidays outside the seeded calendar can appear as possible gaps.",
                total, withData, total - withData, likelyComplete, List.copyOf(results));
    }
}
