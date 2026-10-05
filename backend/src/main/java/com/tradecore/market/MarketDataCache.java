package com.tradecore.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Best-effort Redis cache containing only read-only market quote and instrument data. */
@Component
public class MarketDataCache {
    private static final Logger log = LoggerFactory.getLogger(MarketDataCache.class);
    private static final String PREFIX = "tradecore:market:";
    private static final TypeReference<List<MarketInstrumentResponse>> INSTRUMENT_LIST = new TypeReference<>() { };
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MarketDataCacheProperties properties;

    public MarketDataCache(StringRedisTemplate redis, ObjectMapper objectMapper, MarketDataCacheProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<List<MarketInstrumentResponse>> getAllInstruments() {
        return read(instrumentListKey(), INSTRUMENT_LIST);
    }
    public void putAllInstruments(List<MarketInstrumentResponse> instruments) {
        write(instrumentListKey(), instruments, properties.getInstrumentTtl());
    }
    public Optional<List<MarketInstrumentResponse>> getInstrumentSearch(String query) {
        return read(instrumentSearchKey(query), INSTRUMENT_LIST);
    }
    public void putInstrumentSearch(String query, List<MarketInstrumentResponse> instruments) {
        write(instrumentSearchKey(query), instruments, properties.getInstrumentTtl());
    }
    public Optional<MarketInstrumentResponse> getInstrument(String exchange, String symbol) {
        return read(instrumentKey(exchange, symbol), MarketInstrumentResponse.class);
    }
    public void putInstrument(String exchange, String symbol, MarketInstrumentResponse instrument) {
        write(instrumentKey(exchange, symbol), instrument, properties.getInstrumentTtl());
    }
    public Optional<CachedMarketQuote> getQuote(String exchange, String symbol) {
        return read(quoteKey(exchange, symbol), CachedMarketQuote.class);
    }
    public void putQuote(String exchange, String symbol, CachedMarketQuote quote) {
        write(quoteKey(exchange, symbol), quote, properties.getQuoteTtl());
    }
    public void evictQuote(String exchange, String symbol) {
        String key = quoteKey(exchange, symbol);
        try {
            redis.delete(key);
        } catch (RuntimeException failure) {
            log.warn("Could not evict market quote cache entry {}", key);
        }
    }

    private <T> Optional<T> read(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, type));
        } catch (RuntimeException | java.io.IOException failure) {
            log.warn("Market cache read failed for {}; using PostgreSQL", key);
            return Optional.empty();
        }
    }

    private <T> Optional<T> read(String key, TypeReference<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, type));
        } catch (RuntimeException | java.io.IOException failure) {
            log.warn("Market cache read failed for {}; using PostgreSQL", key);
            return Optional.empty();
        }
    }

    private void write(String key, Object value, java.time.Duration ttl) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (RuntimeException | java.io.IOException failure) {
            log.warn("Market cache write failed for {}", key);
        }
    }

    private static String instrumentListKey() { return PREFIX + "instruments:all"; }
    private static String instrumentSearchKey(String query) {
        return PREFIX + "instruments:search:" + query.toLowerCase(Locale.ROOT);
    }
    private static String instrumentKey(String exchange, String symbol) {
        return PREFIX + "instrument:" + exchange + ":" + symbol;
    }
    private static String quoteKey(String exchange, String symbol) {
        return PREFIX + "quote:" + exchange + ":" + symbol;
    }
}
