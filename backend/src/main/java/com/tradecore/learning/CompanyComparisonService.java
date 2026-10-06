package com.tradecore.learning;

import com.tradecore.market.MarketCandle;
import com.tradecore.market.MarketCandleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CompanyComparisonService {
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final String DAILY_RESOLUTION = "1D";
    private static final int MIN_COMPANIES = 2;
    private static final int MAX_COMPANIES = 4;
    private static final long MAX_RANGE_DAYS = 5L * 366;
    private static final int MAX_ROWS = 5_400;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext METRIC_CONTEXT = MathContext.DECIMAL128;

    private final LearningProfileRepository profiles;
    private final MarketCandleRepository candles;

    public CompanyComparisonService(LearningProfileRepository profiles, MarketCandleRepository candles) {
        this.profiles = profiles;
        this.candles = candles;
    }

    public CompanyComparisonResponse compare(String requestedSymbols, LocalDate from, LocalDate to) {
        List<String> symbols = parseSymbols(requestedSymbols);
        if (from == null || to == null) throw badRequest("from and to dates are required");
        if (to.isBefore(from)) throw badRequest("to must be on or after from");
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw badRequest("Comparison date range cannot exceed five years");
        }
        if (to.isAfter(LocalDate.now(EXCHANGE_ZONE))) throw badRequest("to cannot be in the future");

        List<LearningProfile> found = profiles.findNseProfilesBySymbols(symbols);
        Map<String, LearningProfile> bySymbol = found.stream().collect(Collectors.toMap(
                profile -> profile.getInstrument().getSymbol(), Function.identity()));
        List<String> unsupported = symbols.stream().filter(symbol -> !bySymbol.containsKey(symbol)).toList();
        if (!unsupported.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unsupported NSE symbol(s): " + String.join(", ", unsupported));
        }

        List<LearningProfile> orderedProfiles = symbols.stream().map(bySymbol::get).toList();
        Collection<UUID> instrumentIds = orderedProfiles.stream()
                .map(profile -> profile.getInstrument().getId()).toList();
        Instant fromInclusive = from.atStartOfDay(EXCHANGE_ZONE).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(EXCHANGE_ZONE).toInstant();
        List<MarketCandle> rows = candles.findComparisonCandles(instrumentIds, DAILY_RESOLUTION,
                fromInclusive, toExclusive, PageRequest.of(0, MAX_ROWS + 1));
        if (rows.size() > MAX_ROWS) throw badRequest("Selected date range contains too much persisted candle data");

        Map<UUID, List<MarketCandle>> candlesByInstrument = new LinkedHashMap<>();
        for (MarketCandle row : rows) {
            candlesByInstrument.computeIfAbsent(row.getInstrument().getId(), ignored -> new ArrayList<>()).add(row);
        }
        List<CompanyComparisonResponse.CompanyComparison> metrics = new ArrayList<>();
        List<CompanyComparisonResponse.IndexedCompanySeries> series = new ArrayList<>();
        for (LearningProfile profile : orderedProfiles) {
            List<MarketCandle> history = candlesByInstrument.getOrDefault(profile.getInstrument().getId(), List.of());
            metrics.add(metrics(profile, history));
            series.add(indexed(profile, history));
        }
        return new CompanyComparisonResponse(from, to, List.copyOf(metrics), List.copyOf(series));
    }

    private static List<String> parseSymbols(String requested) {
        if (requested == null || requested.isBlank()) throw badRequest("symbols must contain 2 to 4 supported symbols");
        String[] parts = requested.split(",", -1);
        if (parts.length < MIN_COMPANIES || parts.length > MAX_COMPANIES) {
            throw badRequest("Compare between 2 and 4 symbols");
        }
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String symbol = part.trim().toUpperCase(Locale.ROOT);
            if (!symbol.matches("[A-Z0-9&.-]{1,32}")) throw badRequest("symbols contains a malformed company symbol");
            if (result.contains(symbol)) throw badRequest("symbols cannot contain duplicates");
            result.add(symbol);
        }
        return List.copyOf(result);
    }

    private static CompanyComparisonResponse.CompanyComparison metrics(LearningProfile profile,
            List<MarketCandle> history) {
        var instrument = profile.getInstrument();
        if (history.isEmpty()) {
            return new CompanyComparisonResponse.CompanyComparison(instrument.getSymbol(), instrument.getCompanyName(),
                    profile.getSector(), profile.getBusinessType(), profile.getBusinessDescription(),
                    null, null, null, null, null, null, null, null, 0, null, true,
                    "No persisted daily candles are available in the selected period.");
        }
        MarketCandle first = history.get(0);
        MarketCandle last = history.get(history.size() - 1);
        LocalDate firstDate = tradingDate(first);
        LocalDate lastDate = tradingDate(last);
        BigDecimal absolute = history.size() < 2 ? null
                : last.getClosePrice().subtract(first.getClosePrice()).setScale(4, RoundingMode.HALF_UP);
        BigDecimal percentage = history.size() < 2 ? null
                : percent(last.getClosePrice().subtract(first.getClosePrice()), first.getClosePrice());
        BigDecimal volatility = history.size() < 3 ? null : annualizedVolatility(history);
        BigDecimal drawdown = maximumDrawdown(history);
        String note = history.size() == 1
                ? "Only one persisted daily candle is available; period return and volatility are unavailable."
                : history.size() == 2 ? "Annualized volatility requires at least three persisted daily closes." : null;
        return new CompanyComparisonResponse.CompanyComparison(instrument.getSymbol(), instrument.getCompanyName(),
                profile.getSector(), profile.getBusinessType(), profile.getBusinessDescription(),
                firstDate, first.getClosePrice(), lastDate, last.getClosePrice(), absolute, percentage,
                volatility, drawdown, history.size(), lastDate, history.size() < 2, note);
    }

    private static CompanyComparisonResponse.IndexedCompanySeries indexed(LearningProfile profile,
            List<MarketCandle> history) {
        if (history.isEmpty()) return new CompanyComparisonResponse.IndexedCompanySeries(
                profile.getInstrument().getSymbol(), List.of());
        BigDecimal base = history.get(0).getClosePrice();
        List<CompanyComparisonResponse.IndexedPoint> points = history.stream().map(candle ->
                new CompanyComparisonResponse.IndexedPoint(tradingDate(candle),
                        candle.getClosePrice().multiply(HUNDRED).divide(base, 4, RoundingMode.HALF_UP))).toList();
        return new CompanyComparisonResponse.IndexedCompanySeries(profile.getInstrument().getSymbol(), points);
    }

    private static BigDecimal annualizedVolatility(List<MarketCandle> history) {
        List<BigDecimal> returns = new ArrayList<>(history.size() - 1);
        for (int i = 1; i < history.size(); i++) {
            BigDecimal prior = history.get(i - 1).getClosePrice();
            BigDecimal current = history.get(i).getClosePrice();
            returns.add(current.subtract(prior).divide(prior, METRIC_CONTEXT));
        }
        BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), METRIC_CONTEXT);
        BigDecimal squared = returns.stream().map(value -> value.subtract(mean, METRIC_CONTEXT)
                        .pow(2, METRIC_CONTEXT))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size() - 1), METRIC_CONTEXT);
        double annualizedPercent = Math.sqrt(Math.max(0d, squared.doubleValue())) * Math.sqrt(252d) * 100d;
        return BigDecimal.valueOf(annualizedPercent).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal maximumDrawdown(List<MarketCandle> history) {
        BigDecimal peak = history.get(0).getClosePrice();
        BigDecimal maximum = BigDecimal.ZERO;
        for (MarketCandle candle : history) {
            BigDecimal close = candle.getClosePrice();
            if (close.compareTo(peak) > 0) peak = close;
            BigDecimal drawdown = peak.subtract(close).multiply(HUNDRED)
                    .divide(peak, 8, RoundingMode.HALF_UP);
            if (drawdown.compareTo(maximum) > 0) maximum = drawdown;
        }
        return maximum.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal difference, BigDecimal start) {
        if (start.signum() <= 0) return null;
        return difference.multiply(HUNDRED).divide(start, 4, RoundingMode.HALF_UP);
    }

    private static LocalDate tradingDate(MarketCandle candle) {
        return candle.getBucketStart().atZone(EXCHANGE_ZONE).toLocalDate();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
