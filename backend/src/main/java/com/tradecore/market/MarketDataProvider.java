package com.tradecore.market;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Provider-independent boundary for external market-data integrations. */
public interface MarketDataProvider {

    MarketDataConnectivity checkConnectivity();

    List<MarketInstrument> searchInstruments(String query);

    MarketQuoteSnapshot getQuote(String symbol);

    List<MarketQuoteSnapshot> getQuotes(Collection<String> symbols);

    List<MarketCandleSnapshot> getHistoricalCandles(String symbol, int months, LocalDate endDate);

    Optional<MarketDataFreshness> getDataFreshness();

    Optional<MarketSessionStatus> getMarketSessionStatus();
}
