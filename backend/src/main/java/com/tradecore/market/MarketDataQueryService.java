package com.tradecore.market;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read-only queries for market data already persisted in PostgreSQL. */
@Service
public class MarketDataQueryService {

    private static final String NSE = "NSE";
    private static final String DAILY_RESOLUTION = "1D";
    private static final int DEFAULT_CANDLE_LIMIT = 100;
    private static final int MAX_CANDLE_LIMIT = 500;
    private static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Kolkata");

    private final InstrumentRepository instrumentRepository;
    private final MarketQuoteRepository quoteRepository;
    private final MarketCandleRepository candleRepository;
    private final MarketDataCache cache;

    public MarketDataQueryService(InstrumentRepository instrumentRepository,
            MarketQuoteRepository quoteRepository, MarketCandleRepository candleRepository,
            MarketDataCache cache) {
        this.instrumentRepository = instrumentRepository;
        this.quoteRepository = quoteRepository;
        this.candleRepository = candleRepository;
        this.cache = cache;
    }

    public List<MarketInstrumentResponse> listInstruments(String query) {
        if (query == null) {
            Optional<List<MarketInstrumentResponse>> cached = cache.getAllInstruments();
            if (cached.isPresent()) return cached.get();
        } else {
            if (query.isBlank() || query.length() > 32) {
                throw badRequest("query must contain 1 to 32 characters");
            }
            Optional<List<MarketInstrumentResponse>> cached = cache.getInstrumentSearch(query);
            if (cached.isPresent()) return cached.get();
        }
        List<Instrument> instruments;
        if (query == null) {
            instruments = instrumentRepository.findAllByExchangeAndTradableTrueOrderBySymbolAsc(NSE);
        } else {
            instruments = instrumentRepository
                    .findAllByExchangeAndTradableTrueAndSymbolContainingIgnoreCaseOrderBySymbolAsc(NSE, query);
        }
        List<MarketInstrumentResponse> response = instruments.stream().map(MarketInstrumentResponse::from).toList();
        if (query == null) cache.putAllInstruments(response);
        else cache.putInstrumentSearch(query, response);
        return response;
    }

    public MarketInstrumentResponse getInstrument(String exchange, String symbol) {
        if (exchange == null || exchange.isBlank() || symbol == null || symbol.isBlank()) {
            throw badRequest("exchange and symbol are required");
        }
        Optional<MarketInstrumentResponse> cached = cache.getInstrument(exchange, symbol);
        if (cached.isPresent()) return cached.get();
        MarketInstrumentResponse response = MarketInstrumentResponse.from(requireInstrument(exchange, symbol));
        cache.putInstrument(exchange, symbol, response);
        return response;
    }

    public MarketQuoteResponse getQuote(String exchange, String symbol) {
        if (exchange == null || exchange.isBlank() || symbol == null || symbol.isBlank()) {
            throw badRequest("exchange and symbol are required");
        }
        Instant now = Instant.now();
        Optional<CachedMarketQuote> cached = cache.getQuote(exchange, symbol);
        if (cached.isPresent()) return cached.get().toResponse(now);
        Instrument instrument = requireInstrument(exchange, symbol);
        return quoteRepository.findByInstrument_Id(instrument.getId()).map(quote -> {
            CachedMarketQuote value = CachedMarketQuote.from(quote);
            cache.putQuote(exchange, symbol, value);
            return value.toResponse(now);
        }).orElseGet(() -> MarketQuoteResponse.unavailable(instrument));
    }

    public List<MarketQuoteResponse> getQuotes(Collection<String> requestedSymbols) {
        List<String> symbols = validateSymbols(requestedSymbols);
        List<Instrument> instruments = instrumentRepository
                .findAllByExchangeAndTradableTrueAndSymbolIn(NSE, symbols);
        Map<String, Instrument> instrumentsBySymbol = new HashMap<>();
        instruments.forEach(instrument -> instrumentsBySymbol.put(instrument.getSymbol(), instrument));
        if (!instrumentsBySymbol.keySet().equals(new HashSet<>(symbols))) {
            Set<String> unsupported = new HashSet<>(symbols);
            unsupported.removeAll(instrumentsBySymbol.keySet());
            throw notFound("Unsupported NSE symbol(s): " + String.join(", ", unsupported));
        }

        Map<String, CachedMarketQuote> quotesBySymbol = new HashMap<>();
        List<String> cacheMisses = new ArrayList<>();
        for (String symbol : symbols) {
            Optional<CachedMarketQuote> cached = cache.getQuote(NSE, symbol);
            if (cached.isPresent()) quotesBySymbol.put(symbol, cached.get());
            else cacheMisses.add(symbol);
        }
        if (!cacheMisses.isEmpty()) {
            List<MarketQuote> quotes = quoteRepository
                    .findAllByInstrument_ExchangeAndInstrument_SymbolIn(NSE, cacheMisses);
            Map<UUID, String> symbolsByInstrumentId = new HashMap<>();
            instrumentsBySymbol.forEach((symbol, instrument) -> symbolsByInstrumentId.put(instrument.getId(), symbol));
            for (MarketQuote quote : quotes) {
                String symbol = symbolsByInstrumentId.get(quote.getInstrument().getId());
                if (symbol != null) {
                    CachedMarketQuote cached = CachedMarketQuote.from(quote);
                    quotesBySymbol.put(symbol, cached);
                    cache.putQuote(NSE, symbol, cached);
                }
            }
        }
        Instant now = Instant.now();
        return symbols.stream().map(symbol -> {
            Instrument instrument = instrumentsBySymbol.get(symbol);
            CachedMarketQuote quote = quotesBySymbol.get(symbol);
            return quote == null ? MarketQuoteResponse.unavailable(instrument) : quote.toResponse(now);
        }).toList();
    }

    public MarketCandleHistoryResponse getDailyCandles(
            String exchange, String symbol, LocalDate from, LocalDate to, Integer requestedLimit) {
        Instrument instrument = requireInstrument(exchange, symbol);
        if (from == null || to == null || to.isBefore(from)) {
            throw badRequest("from and to are required and to must be on or after from");
        }
        int limit = requestedLimit == null ? DEFAULT_CANDLE_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_CANDLE_LIMIT) {
            throw badRequest("limit must be between 1 and " + MAX_CANDLE_LIMIT);
        }
        Instant fromInclusive = from.atStartOfDay(EXCHANGE_ZONE).toInstant();
        Instant toExclusive;
        try {
            toExclusive = to.plusDays(1).atStartOfDay(EXCHANGE_ZONE).toInstant();
        } catch (RuntimeException exception) {
            throw badRequest("to is outside the supported date range");
        }
        List<MarketCandleResponse> candles = candleRepository
                .findAllByInstrument_IdAndResolutionAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
                        instrument.getId(), DAILY_RESOLUTION, fromInclusive, toExclusive, PageRequest.of(0, limit))
                .stream().map(MarketCandleResponse::from).toList();
        return new MarketCandleHistoryResponse(
                instrument.getSymbol(), instrument.getExchange(), DAILY_RESOLUTION, from, to, limit, candles);
    }

    private Instrument requireInstrument(String exchange, String symbol) {
        if (exchange == null || exchange.isBlank() || symbol == null || symbol.isBlank()) {
            throw badRequest("exchange and symbol are required");
        }
        return instrumentRepository.findByExchangeAndSymbol(exchange, symbol)
                .filter(Instrument::isTradable)
                .orElseThrow(() -> notFound("Unsupported instrument: " + exchange + ":" + symbol));
    }

    private static List<String> validateSymbols(Collection<String> requestedSymbols) {
        if (requestedSymbols == null || requestedSymbols.isEmpty() || requestedSymbols.size() > 80) {
            throw badRequest("symbols must contain between 1 and 80 supported symbols");
        }
        List<String> symbols = new ArrayList<>(requestedSymbols);
        if (symbols.stream().anyMatch(symbol -> symbol == null || symbol.isBlank() || symbol.length() > 32)) {
            throw badRequest("symbols contain an invalid value");
        }
        if (new HashSet<>(symbols).size() != symbols.size()) {
            throw badRequest("symbols must be unique");
        }
        return symbols;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
