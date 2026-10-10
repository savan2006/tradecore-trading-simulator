package com.tradecore.market;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only REST API backed only by persisted PostgreSQL market data. */
@Tag(name = "Market")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/market")
public class MarketDataQueryController {

    private final MarketDataQueryService queryService;
    private final MarketScreenerService screenerService;

    public MarketDataQueryController(MarketDataQueryService queryService, MarketScreenerService screenerService) {
        this.queryService = queryService;
        this.screenerService = screenerService;
    }

    @Operation(summary = "Screener", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping("/screener")
    public List<MarketScreenerResponse> screener(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sector,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer limit) {
        return screenerService.screen(search, sector, sort, limit);
    }

    @Operation(summary = "Instruments", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping("/instruments")
    public List<MarketInstrumentResponse> instruments(@RequestParam(required = false) String query) {
        return queryService.listInstruments(query);
    }

    @Operation(summary = "Instrument", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found")})

    @GetMapping("/instruments/{exchange}/{symbol}")
    public MarketInstrumentResponse instrument(@PathVariable String exchange, @PathVariable String symbol) {
        return queryService.getInstrument(exchange, symbol);
    }

    @Operation(summary = "Quotes", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping("/quotes")
    public List<MarketQuoteResponse> quotes(@RequestParam List<String> symbols) {
        return queryService.getQuotes(symbols);
    }

    @Operation(summary = "Quote", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found")})

    @GetMapping("/quotes/{exchange}/{symbol}")
    public MarketQuoteResponse quote(@PathVariable String exchange, @PathVariable String symbol) {
        return queryService.getQuote(exchange, symbol);
    }

    @Operation(summary = "Daily Candles", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "404", description = "The requested resource was not found")})

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
