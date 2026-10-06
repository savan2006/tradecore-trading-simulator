package com.tradecore.market;

import com.tradecore.learning.LearningProfile;
import com.tradecore.learning.LearningProfileRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MarketScreenerService {
    private static final String NSE = "NSE";
    private static final String DAILY = "1D";
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final int MAX_RESULTS = 80;
    private final InstrumentRepository instruments;
    private final LearningProfileRepository profiles;
    private final MarketQuoteRepository quotes;
    private final MarketCandleRepository candles;

    public MarketScreenerService(InstrumentRepository instruments, LearningProfileRepository profiles,
            MarketQuoteRepository quotes, MarketCandleRepository candles) {
        this.instruments = instruments;
        this.profiles = profiles;
        this.quotes = quotes;
        this.candles = candles;
    }

    @Transactional(readOnly = true)
    public List<MarketScreenerResponse> screen(String search, String sector, String sort, Integer requestedLimit) {
        int limit = requestedLimit == null ? 50 : requestedLimit;
        if (limit < 1 || limit > MAX_RESULTS) throw badRequest("limit must be between 1 and 80");
        String normalizedSearch = search == null ? null : search.trim().toLowerCase(Locale.ROOT);
        if (normalizedSearch != null && (normalizedSearch.isEmpty() || normalizedSearch.length() > 160))
            throw badRequest("search must contain 1 to 160 characters");
        String normalizedSector = sector == null ? null : sector.trim();
        if (normalizedSector != null && (normalizedSector.isEmpty() || normalizedSector.length() > 100))
            throw badRequest("sector must contain 1 to 100 characters");
        String selectedSort = sort == null ? "DEFAULT" : sort.trim().toUpperCase(Locale.ROOT);
        Comparator<MarketScreenerResponse> comparator = comparator(selectedSort);

        List<Instrument> universe = instruments.findAllByExchangeAndTradableTrueOrderBySymbolAsc(NSE);
        List<String> symbols = universe.stream().map(Instrument::getSymbol).toList();
        List<LearningProfile> profileRows = symbols.isEmpty() ? List.of() : profiles.findNseProfilesBySymbols(symbols);
        Map<String, LearningProfile> profileBySymbol = new HashMap<>();
        profileRows.forEach(p -> profileBySymbol.put(p.getInstrument().getSymbol(), p));
        Map<String, MarketQuote> quoteBySymbol = new HashMap<>();
        if (!symbols.isEmpty()) quotes.findAllByInstrument_ExchangeAndInstrument_SymbolIn(NSE, symbols)
                .forEach(q -> quoteBySymbol.put(q.getInstrument().getSymbol(), q));

        LocalDate today = LocalDate.now(EXCHANGE_ZONE);
        Instant from = today.minusDays(366).atStartOfDay(EXCHANGE_ZONE).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(EXCHANGE_ZONE).toInstant();
        List<UUID> ids = universe.stream().map(Instrument::getId).toList();
        List<MarketCandle> historical = ids.isEmpty() ? List.of()
                : candles.findComparisonCandles(ids, DAILY, from, to, PageRequest.of(0, MAX_RESULTS * 300));
        Map<UUID, List<MarketCandle>> candleByInstrument = new HashMap<>();
        historical.forEach(c -> candleByInstrument.computeIfAbsent(c.getInstrument().getId(), ignored -> new ArrayList<>()).add(c));

        Instant now = Instant.now();
        List<MarketScreenerResponse> result = new ArrayList<>();
        for (Instrument instrument : universe) {
            LearningProfile profile = profileBySymbol.get(instrument.getSymbol());
            if (normalizedSector != null && (profile == null || !profile.getSector().equalsIgnoreCase(normalizedSector))) continue;
            if (normalizedSearch != null && !instrument.getSymbol().toLowerCase(Locale.ROOT).contains(normalizedSearch)
                    && !instrument.getCompanyName().toLowerCase(Locale.ROOT).contains(normalizedSearch)) continue;
            MarketQuote quote = quoteBySymbol.get(instrument.getSymbol());
            MarketQuoteResponse quoteView = quote == null ? MarketQuoteResponse.unavailable(instrument) : MarketQuoteResponse.from(quote, now);
            List<MarketCandle> series = candleByInstrument.getOrDefault(instrument.getId(), List.of());
            BigDecimal high = series.stream().map(MarketCandle::getHighPrice).filter(v -> v != null).max(BigDecimal::compareTo).orElse(null);
            BigDecimal low = series.stream().map(MarketCandle::getLowPrice).filter(v -> v != null).min(BigDecimal::compareTo).orElse(null);
            BigDecimal ltp = quoteView.lastPrice();
            BigDecimal dailyChange = "LIVE".equals(quoteView.dataStatus()) && positive(quoteView.previousClose()) && positive(ltp)
                    ? ltp.subtract(quoteView.previousClose()).multiply(BigDecimal.valueOf(100)).divide(quoteView.previousClose(), MC) : null;
            BigDecimal distanceHigh = positive(high) && positive(ltp)
                    ? high.subtract(ltp).multiply(BigDecimal.valueOf(100)).divide(high, MC) : null;
            BigDecimal distanceLow = positive(low) && positive(ltp)
                    ? ltp.subtract(low).multiply(BigDecimal.valueOf(100)).divide(low, MC) : null;
            result.add(new MarketScreenerResponse(instrument.getSymbol(), instrument.getCompanyName(),
                    profile == null ? null : profile.getSector(), profile == null ? null : profile.getBusinessType(),
                    ltp, dailyChange, quoteView.volume(), volatility(series), high, low, distanceHigh, distanceLow,
                    quoteView.dataStatus(), quote == null ? "UNKNOWN" : quote.getMarketStatus()));
        }
        result.sort(comparator);
        return result.stream().limit(limit).toList();
    }

    private static BigDecimal volatility(List<MarketCandle> candles) {
        if (candles.size() < 3) return null;
        List<BigDecimal> returns = new ArrayList<>();
        BigDecimal previous = null;
        for (MarketCandle candle : candles) {
            BigDecimal close = candle.getClosePrice();
            if (!positive(close)) continue;
            if (previous != null) returns.add(close.subtract(previous).divide(previous, MC));
            previous = close;
        }
        if (returns.size() < 2) return null;
        double mean = returns.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        double variance = returns.stream().mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2)).sum() / (returns.size() - 1);
        return BigDecimal.valueOf(Math.sqrt(variance * 252.0) * 100.0).setScale(4, RoundingMode.HALF_UP);
    }

    private static Comparator<MarketScreenerResponse> comparator(String sort) {
        Comparator<MarketScreenerResponse> symbol = Comparator.comparing(MarketScreenerResponse::symbol);
        return switch (sort) {
            case "DEFAULT" -> symbol;
            case "TOP_GAINERS" -> Comparator.comparing(MarketScreenerResponse::dailyChangePercent,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(symbol);
            case "TOP_LOSERS" -> Comparator.comparing(MarketScreenerResponse::dailyChangePercent,
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(symbol);
            case "HIGHEST_VOLUME" -> Comparator.comparing(MarketScreenerResponse::volume,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(symbol);
            case "HIGHEST_VOLATILITY" -> Comparator.comparing(MarketScreenerResponse::volatilityPercent,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(symbol);
            case "NEAR_52_WEEK_HIGH" -> Comparator.comparing(MarketScreenerResponse::distanceFromFiftyTwoWeekHighPercent,
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(symbol);
            case "NEAR_52_WEEK_LOW" -> Comparator.comparing(MarketScreenerResponse::distanceFromFiftyTwoWeekLowPercent,
                    Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(symbol);
            default -> throw badRequest("sort must be DEFAULT, TOP_GAINERS, TOP_LOSERS, HIGHEST_VOLUME, HIGHEST_VOLATILITY, NEAR_52_WEEK_HIGH, or NEAR_52_WEEK_LOW");
        };
    }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
