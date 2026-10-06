package com.tradecore.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class MarketDataIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataIngestionService.class);

    private static final String EXCHANGE = "NSE";
    private static final String EQUITY = "EQUITY";
    private static final String DAILY_RESOLUTION = "1D";
    private static final String UNKNOWN_MARKET_STATUS = "UNKNOWN";
    private static final int SUPPORTED_UNIVERSE_SIZE = 80;
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");

    private final MarketDataProvider provider;
    private final InstrumentRepository instrumentRepository;
    private final MarketQuoteRepository quoteRepository;
    private final MarketCandleRepository candleRepository;
    private final MarketDataQueryService queryService;
    private final MarketDataCache marketDataCache;
    private final MarketQuoteWebSocketHandler quoteStream;
    private final TransactionTemplate transactionTemplate;

    public MarketDataIngestionService(
            MarketDataProvider provider,
            InstrumentRepository instrumentRepository,
            MarketQuoteRepository quoteRepository,
            MarketCandleRepository candleRepository,
            MarketDataQueryService queryService,
            MarketDataCache marketDataCache,
            MarketQuoteWebSocketHandler quoteStream,
            PlatformTransactionManager transactionManager) {
        this.provider = provider;
        this.instrumentRepository = instrumentRepository;
        this.quoteRepository = quoteRepository;
        this.candleRepository = candleRepository;
        this.queryService = queryService;
        this.marketDataCache = marketDataCache;
        this.quoteStream = quoteStream;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public MarketDataIngestionResult ingestCurrentQuotes() {
        return ingestCurrentQuotes(null);
    }

    /** Ingests all approved NSE equities, or a non-empty approved subset for controlled verification. */
    public MarketDataIngestionResult ingestCurrentQuotes(Collection<String> requestedSymbols) {
        Map<String, Instrument> instruments = approvedInstrumentMap();
        List<Instrument> selected = selectInstruments(instruments, requestedSymbols);
        List<String> symbols = selected.stream().map(Instrument::getSymbol).toList();

        List<MarketQuoteSnapshot> quotes = provider.getQuotes(symbols);
        MarketDataFreshness freshness = provider.getDataFreshness().orElse(null);
        validateFreshness(freshness);
        Map<String, MarketQuoteSnapshot> quotesBySymbol = validateQuotes(selected, quotes);
        Instant ingestedAt = Instant.now();
        List<String> changedSymbols = new ArrayList<>();

        MarketDataIngestionResult result = transactionTemplate.execute(status -> {
            int inserted = 0;
            int updated = 0;
            int stale = 0;
            for (Instrument instrument : selected) {
                MarketQuoteSnapshot quote = quotesBySymbol.get(instrument.getSymbol());
                String dataStatus = isFresh(quote, freshness, ingestedAt) ? "LIVE" : "STALE";
                if ("STALE".equals(dataStatus)) {
                    stale++;
                }
                Instrument managedInstrument = instrumentRepository.getReferenceById(instrument.getId());
                var existing = quoteRepository.findByInstrument_Id(instrument.getId());
                if (existing.isPresent()) {
                    MarketQuote current = existing.get();
                    boolean changed = quoteChanged(current, quote, UNKNOWN_MARKET_STATUS, dataStatus);
                    current.updateFrom(quote, ingestedAt, UNKNOWN_MARKET_STATUS, dataStatus);
                    if (changed) changedSymbols.add(instrument.getSymbol());
                    updated++;
                } else {
                    quoteRepository.save(new MarketQuote(
                            managedInstrument, quote, ingestedAt, UNKNOWN_MARKET_STATUS, dataStatus));
                    changedSymbols.add(instrument.getSymbol());
                    inserted++;
                }
            }
            return new MarketDataIngestionResult(quotes.size(), inserted, updated, 0, stale);
        });
        // The transaction above has committed. Drop every persisted symbol so subsequent reads load
        // the committed row; WebSocket publication below may then repopulate it with the same value.
        for (Instrument instrument : selected) {
            marketDataCache.evictQuote(EXCHANGE, instrument.getSymbol());
        }
        for (String symbol : changedSymbols) {
            try {
                quoteStream.publish(queryService.getQuote(EXCHANGE, symbol));
            } catch (RuntimeException publishFailure) {
                // Persistence has committed; a WebSocket/read failure must not change ingestion outcome.
                log.warn("Could not publish persisted quote update for {}", symbol, publishFailure);
            }
        }
        return result;
    }

    private static boolean quoteChanged(MarketQuote current, MarketQuoteSnapshot next,
            String marketStatus, String dataStatus) {
        return !sameDecimal(current.getLastPrice(), next.lastPrice())
                || !sameDecimal(current.getPreviousClose(), next.previousClose())
                || !sameDecimal(current.getOpenPrice(), next.open())
                || !sameDecimal(current.getHighPrice(), next.high())
                || !sameDecimal(current.getLowPrice(), next.low())
                || !Objects.equals(current.getVolume(), next.volume())
                || !Objects.equals(current.getMarketAt(), next.tradingTimestamp())
                || !Objects.equals(current.getProviderUpdatedAt(), next.dataUpdatedAt())
                || !Objects.equals(current.getMarketStatus(), marketStatus)
                || !Objects.equals(current.getDataStatus(), dataStatus);
    }

    private static boolean sameDecimal(BigDecimal current, BigDecimal next) {
        return current == null ? next == null : next != null && current.compareTo(next) == 0;
    }

    /** Ingests one bounded month of daily candles for all approved NSE equities or the requested subset. */
    public MarketDataIngestionResult ingestRecentDailyCandles() {
        return ingestRecentDailyCandles(null);
    }

    public MarketDataIngestionResult ingestRecentDailyCandles(Collection<String> requestedSymbols) {
        Map<String, Instrument> instruments = approvedInstrumentMap();
        List<Instrument> selected = selectInstruments(instruments, requestedSymbols);
        Map<String, List<MarketCandleSnapshot>> candlesBySymbol = new HashMap<>();
        int received = 0;
        for (Instrument instrument : selected) {
            List<MarketCandleSnapshot> snapshots = provider.getHistoricalCandles(
                    instrument.getSymbol(), 1, null);
            validateCandles(instrument, snapshots);
            if (snapshots.isEmpty()) {
                throw unavailable("NSE returned no historical candles for " + instrument.getSymbol());
            }
            candlesBySymbol.put(instrument.getSymbol(), snapshots);
            received += snapshots.size();
        }
        Instant ingestedAt = Instant.now();
        int totalReceived = received;

        return transactionTemplate.execute(status -> {
            int inserted = 0;
            int skipped = 0;
            for (Instrument instrument : selected) {
                Instrument managedInstrument = instrumentRepository.getReferenceById(instrument.getId());
                for (MarketCandleSnapshot candle : candlesBySymbol.get(instrument.getSymbol())) {
                    Instant bucketStart = candle.tradingDate().atStartOfDay(EXCHANGE_ZONE).toInstant();
                    boolean exists = candleRepository.existsByInstrument_IdAndResolutionAndBucketStart(
                            instrument.getId(), DAILY_RESOLUTION, bucketStart);
                    if (exists) {
                        skipped++;
                    } else {
                        candleRepository.save(new MarketCandle(
                                managedInstrument, DAILY_RESOLUTION, bucketStart, candle, ingestedAt));
                        inserted++;
                    }
                }
            }
            return new MarketDataIngestionResult(totalReceived, inserted, 0, skipped, 0);
        });
    }

    /** Resolves only the approved NSE universe once for a controlled historical backfill run. */
    public List<Instrument> resolveHistoricalBackfillInstruments(Collection<String> requestedSymbols) {
        return selectInstruments(approvedInstrumentMap(), requestedSymbols);
    }

    /**
     * Backfills one instrument in sequential provider windows of at most three months.
     * Each successfully normalized window is committed independently so a later timeout can be resumed.
     */
    public MarketDataIngestionResult backfillDailyCandles(Instrument instrument, int months, LocalDate throughDate) {
        if (instrument == null || !EXCHANGE.equals(instrument.getExchange()) || !instrument.isTradable()) {
            throw unavailable("Historical backfill requires an approved tradable NSE instrument");
        }
        if (months < 1 || months > 36 || throughDate == null
                || throughDate.isAfter(LocalDate.now(EXCHANGE_ZONE).minusDays(1))) {
            throw new IllegalArgumentException("Backfill period must be 1 to 36 months and end no later than yesterday");
        }

        LocalDate fromDate = throughDate.minusMonths(months);
        LocalDate cursor = fromDate;
        int remainingMonths = months;
        int received = 0;
        int inserted = 0;
        int skipped = 0;
        try {
            while (remainingMonths > 0) {
                int chunkMonths = Math.min(3, remainingMonths);
                LocalDate chunkToExclusive = remainingMonths == chunkMonths
                        ? throughDate.plusDays(1) : cursor.plusMonths(chunkMonths);
                LocalDate providerEndDate = chunkToExclusive.minusDays(1);
                LocalDate chunkFrom = cursor;
                List<MarketCandleSnapshot> snapshots = provider.getHistoricalCandles(
                        instrument.getSymbol(), chunkMonths, providerEndDate);
                validateCandles(instrument, snapshots);
                List<MarketCandleSnapshot> inWindow = snapshots.stream()
                        .filter(snapshot -> !snapshot.tradingDate().isBefore(chunkFrom)
                                && snapshot.tradingDate().isBefore(chunkToExclusive)
                                && !snapshot.tradingDate().isAfter(throughDate))
                        .toList();
                MarketDataIngestionResult chunkResult = persistHistoricalCandleWindow(
                        instrument, inWindow, cursor, chunkToExclusive);
                received += chunkResult.received();
                inserted += chunkResult.inserted();
                skipped += chunkResult.skipped();
                cursor = chunkToExclusive;
                remainingMonths -= chunkMonths;
            }
        } catch (RuntimeException failure) {
            throw new HistoricalBackfillPartialFailure(failure,
                    new MarketDataIngestionResult(received, inserted, 0, skipped, 0));
        }
        return new MarketDataIngestionResult(received, inserted, 0, skipped, 0);
    }

    private MarketDataIngestionResult persistHistoricalCandleWindow(Instrument instrument,
            List<MarketCandleSnapshot> snapshots, LocalDate fromDate, LocalDate toExclusiveDate) {
        Instant fromInclusive = fromDate.atStartOfDay(EXCHANGE_ZONE).toInstant();
        Instant toExclusive = toExclusiveDate.atStartOfDay(EXCHANGE_ZONE).toInstant();
        List<MarketCandle> existingRows = candleRepository
                .findAllByInstrument_IdAndResolutionAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                        instrument.getId(), DAILY_RESOLUTION, fromInclusive, toExclusive, org.springframework.data.domain.PageRequest.of(0, 100));
        Set<Instant> existingBuckets = existingRows.stream().map(MarketCandle::getBucketStart).collect(Collectors.toSet());
        Instant ingestedAt = Instant.now();
        return transactionTemplate.execute(status -> {
            List<MarketCandle> missing = new ArrayList<>();
            int skipped = 0;
            Instrument managedInstrument = instrumentRepository.getReferenceById(instrument.getId());
            for (MarketCandleSnapshot candle : snapshots) {
                Instant bucketStart = candle.tradingDate().atStartOfDay(EXCHANGE_ZONE).toInstant();
                if (existingBuckets.contains(bucketStart)) {
                    skipped++;
                } else {
                    missing.add(new MarketCandle(managedInstrument, DAILY_RESOLUTION, bucketStart, candle, ingestedAt));
                    existingBuckets.add(bucketStart);
                }
            }
            candleRepository.saveAll(missing);
            return new MarketDataIngestionResult(snapshots.size(), missing.size(), 0, skipped, 0);
        });
    }

    private Map<String, Instrument> approvedInstrumentMap() {
        List<Instrument> approved = instrumentRepository.findAllByExchangeAndTradableTrue(EXCHANGE);
        if (approved.size() != SUPPORTED_UNIVERSE_SIZE) {
            throw new IllegalStateException("Expected exactly 80 tradable NSE instruments; found " + approved.size());
        }
        return approved.stream().collect(Collectors.toMap(Instrument::getSymbol, Function.identity()));
    }

    private static List<Instrument> selectInstruments(Map<String, Instrument> approved,
            Collection<String> requestedSymbols) {
        if (requestedSymbols == null) {
            return approved.values().stream().sorted(java.util.Comparator.comparing(Instrument::getSymbol)).toList();
        }
        if (requestedSymbols.isEmpty()) {
            throw new IllegalArgumentException("At least one approved symbol is required");
        }
        Set<String> unique = new HashSet<>(requestedSymbols);
        if (unique.size() != requestedSymbols.size()) {
            throw new IllegalArgumentException("Requested symbols must be unique");
        }
        List<Instrument> selected = new ArrayList<>();
        for (String symbol : requestedSymbols) {
            Instrument instrument = approved.get(symbol);
            if (instrument == null) {
                throw unavailable("Instrument is not in the approved NSE universe: " + symbol);
            }
            selected.add(instrument);
        }
        return List.copyOf(selected);
    }

    private static Map<String, MarketQuoteSnapshot> validateQuotes(
            List<Instrument> instruments, List<MarketQuoteSnapshot> quotes) {
        if (quotes == null) {
            throw malformed("Provider returned no normalized quote collection");
        }
        Set<String> requested = instruments.stream().map(Instrument::getSymbol).collect(Collectors.toSet());
        Map<String, MarketQuoteSnapshot> quotesBySymbol = new HashMap<>();
        for (MarketQuoteSnapshot quote : quotes) {
            if (quote == null || quote.symbol() == null || !requested.contains(quote.symbol())) {
                throw malformed("Provider returned a quote for a missing or unrequested instrument");
            }
            if (quotesBySymbol.putIfAbsent(quote.symbol(), quote) != null) {
                throw malformed("Provider returned a duplicate quote for " + quote.symbol());
            }
            if (!EXCHANGE.equals(quote.exchange())
                    || quote.lastPrice() == null || quote.open() == null
                    || quote.high() == null || quote.low() == null || quote.volume() == null) {
                throw malformed("Quote is missing required NSE market values for " + quote.symbol());
            }
            validatePositive(quote.lastPrice(), "last price", quote.symbol());
            validatePositive(quote.open(), "open", quote.symbol());
            validatePositive(quote.high(), "high", quote.symbol());
            validatePositive(quote.low(), "low", quote.symbol());
            if (quote.previousClose() != null && quote.previousClose().signum() < 0) {
                throw malformed("Quote has a negative previous close for " + quote.symbol());
            }
            if (quote.volume() < 0 || quote.high().compareTo(quote.low()) < 0
                    || quote.open().compareTo(quote.low()) < 0 || quote.open().compareTo(quote.high()) > 0
                    || quote.lastPrice().compareTo(quote.low()) < 0 || quote.lastPrice().compareTo(quote.high()) > 0) {
                throw malformed("Quote has invalid OHLCV values for " + quote.symbol());
            }
        }
        if (!quotesBySymbol.keySet().equals(requested)) {
            Set<String> missing = new HashSet<>(requested);
            missing.removeAll(quotesBySymbol.keySet());
            throw unavailable("NSE returned no current quote for " + String.join(", ", missing));
        }
        return quotesBySymbol;
    }

    private static void validateCandles(Instrument instrument, List<MarketCandleSnapshot> candles) {
        if (candles == null) {
            throw malformed("Provider returned no normalized candle collection for " + instrument.getSymbol());
        }
        Set<LocalDate> dates = new HashSet<>();
        for (MarketCandleSnapshot candle : candles) {
            if (candle == null || candle.tradingDate() == null || candle.open() == null || candle.high() == null
                    || candle.low() == null || candle.close() == null || candle.volume() == null) {
                throw malformed("Historical candle is missing required values for " + instrument.getSymbol());
            }
            if (!EXCHANGE.equals(candle.exchange()) || !instrument.getSymbol().equals(candle.symbol())) {
                throw malformed("Historical candle does not match its approved NSE instrument");
            }
            if (candle.tradingTimestamp() != null) {
                throw malformed("Daily historical candle unexpectedly supplied an intraday timestamp");
            }
            validatePositive(candle.open(), "candle open", candle.symbol());
            validatePositive(candle.high(), "candle high", candle.symbol());
            validatePositive(candle.low(), "candle low", candle.symbol());
            validatePositive(candle.close(), "candle close", candle.symbol());
            if (candle.volume() < 0 || candle.high().compareTo(candle.low()) < 0
                    || candle.open().compareTo(candle.low()) < 0 || candle.open().compareTo(candle.high()) > 0
                    || candle.close().compareTo(candle.low()) < 0 || candle.close().compareTo(candle.high()) > 0) {
                throw malformed("Historical candle has invalid OHLCV values for " + candle.symbol());
            }
            if (!dates.add(candle.tradingDate())) {
                throw malformed("Provider returned a duplicate daily candle for " + candle.symbol());
            }
        }
    }

    private static void validateFreshness(MarketDataFreshness freshness) {
        if (freshness == null) {
            return;
        }
        if (!freshness.available()) {
            throw unavailable("NSE MCP reports current equity data unavailable");
        }
        Duration expectedRefreshInterval = freshness.expectedRefreshInterval();
        if (expectedRefreshInterval != null && (expectedRefreshInterval.isZero() || expectedRefreshInterval.isNegative())) {
            throw malformed("Provider supplied an invalid market-data refresh interval");
        }
    }

    private static boolean isFresh(MarketQuoteSnapshot quote, MarketDataFreshness freshness, Instant now) {
        if (freshness == null || freshness.dataUpdatedAt() == null
                || freshness.expectedRefreshInterval() == null || quote.dataUpdatedAt() == null) {
            return false;
        }
        Duration interval = freshness.expectedRefreshInterval();
        Instant cutoff = now.minus(interval);
        return !freshness.dataUpdatedAt().isBefore(cutoff) && !quote.dataUpdatedAt().isBefore(cutoff);
    }

    private static void validatePositive(BigDecimal value, String field, String symbol) {
        if (value.signum() <= 0) {
            throw malformed("Quote/candle has a non-positive " + field + " for " + symbol);
        }
    }

    private static MarketDataProviderException malformed(String message) {
        return new MarketDataProviderException(MarketDataProviderException.Category.MALFORMED_RESPONSE, message);
    }

    private static MarketDataProviderException unavailable(String message) {
        return new MarketDataProviderException(MarketDataProviderException.Category.UNAVAILABLE_DATA, message);
    }
}
