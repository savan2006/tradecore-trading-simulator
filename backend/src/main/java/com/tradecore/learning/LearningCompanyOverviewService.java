package com.tradecore.learning;

import com.tradecore.market.MarketCandle;
import com.tradecore.market.MarketCandleRepository;
import com.tradecore.market.MarketCandleResponse;
import com.tradecore.market.MarketQuote;
import com.tradecore.market.MarketQuoteRepository;
import com.tradecore.market.MarketQuoteResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class LearningCompanyOverviewService {
    private static final String DAILY_RESOLUTION = "1D";
    private static final int DEFAULT_CANDLE_LIMIT = 30;
    private static final int MAX_CANDLE_LIMIT = 90;

    private final LearningProfileRepository profiles;
    private final MarketQuoteRepository quotes;
    private final MarketCandleRepository candles;

    public LearningCompanyOverviewService(LearningProfileRepository profiles,
            MarketQuoteRepository quotes, MarketCandleRepository candles) {
        this.profiles = profiles;
        this.quotes = quotes;
        this.candles = candles;
    }

    public LearningCompanyOverviewResponse overview(String symbol, Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_CANDLE_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_CANDLE_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX_CANDLE_LIMIT);
        }

        LearningProfile profile = profiles.findNseProfileBySymbol(symbol.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Learning profile not found"));
        var instrument = profile.getInstrument();
        Instant now = Instant.now();
        MarketQuoteResponse quoteResponse = quotes.findByInstrument_Id(instrument.getId())
                .map(quote -> MarketQuoteResponse.from(quote, now))
                .orElseGet(() -> MarketQuoteResponse.unavailable(instrument));

        List<MarketCandle> latestFirst = candles.findAllByInstrument_IdAndResolutionOrderByBucketStartDesc(
                instrument.getId(), DAILY_RESOLUTION, PageRequest.of(0, limit));
        List<MarketCandleResponse> candleResponses = new ArrayList<>(latestFirst.stream()
                .map(MarketCandleResponse::from).toList());
        java.util.Collections.reverse(candleResponses);

        LearningPriceChange quoteChange = "LIVE".equals(quoteResponse.dataStatus())
                ? change(quoteResponse.lastPrice(), quoteResponse.previousClose()) : null;
        LearningPriceChange candleChange = candlePeriodChange(latestFirst);
        return new LearningCompanyOverviewResponse(LearningProfileResponse.from(profile), quoteResponse,
                List.copyOf(candleResponses), quoteChange, candleChange, quoteResponse.dataStatus());
    }

    private static LearningPriceChange candlePeriodChange(List<MarketCandle> latestFirst) {
        if (latestFirst.size() < 2) return null;
        BigDecimal latestClose = latestFirst.get(0).getClosePrice();
        BigDecimal earliestClose = latestFirst.get(latestFirst.size() - 1).getClosePrice();
        return change(latestClose, earliestClose);
    }

    private static LearningPriceChange change(BigDecimal current, BigDecimal base) {
        if (current == null || base == null || base.signum() <= 0) return null;
        BigDecimal absolute = current.subtract(base).setScale(4, RoundingMode.HALF_UP);
        BigDecimal percent = absolute.multiply(BigDecimal.valueOf(100))
                .divide(base, 4, RoundingMode.HALF_UP);
        return new LearningPriceChange(absolute, percent);
    }
}
