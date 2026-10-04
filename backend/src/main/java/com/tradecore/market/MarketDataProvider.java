package com.tradecore.market;

/** Provider-independent boundary for external market-data integrations. */
public interface MarketDataProvider {

    MarketDataConnectivity checkConnectivity();
}
