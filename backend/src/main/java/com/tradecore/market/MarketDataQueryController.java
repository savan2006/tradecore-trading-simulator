package com.tradecore.market;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only REST API backed only by persisted PostgreSQL market data. */
@RestController
@RequestMapping("/api/v1/market")
public class MarketDataQueryController {

    private final MarketDataQueryService queryService;

    public MarketDataQueryController(MarketDataQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/instruments")
    public List<MarketInstrumentResponse> instruments(@RequestParam(required = false) String query) {
        return queryService.listInstruments(query);
    }

    @GetMapping("/instruments/{exchange}/{symbol}")
    public MarketInstrumentResponse instrument(@PathVariable String exchange, @PathVariable String symbol) {
        return queryService.getInstrument(exchange, symbol);
    }

    @GetMapping("/quotes")
    public List<MarketQuoteResponse> quotes(@RequestParam List<String> symbols) {
        return queryService.getQuotes(symbols);
    }

    @GetMapping("/quotes/{exchange}/{symbol}")
    public MarketQuoteResponse quote(@PathVariable String exchange, @PathVariable String symbol) {
        return queryService.getQuote(exchange, symbol);
    }

    @GetMapping("/instruments/{exchange}/{symbol}/candles")
    public MarketCandleHistoryResponse dailyCandles(
            @PathVariable String exchange,
            @PathVariable String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer limit) {
        return queryService.getDailyCandles(exchange, symbol, from, to, limit);
    }
}
