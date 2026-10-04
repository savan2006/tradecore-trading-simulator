package com.tradecore.market;

/** REST representation of a supported TradeCore instrument. */
public record MarketInstrumentResponse(
        String symbol,
        String companyName,
        String exchange,
        String instrumentType,
        String currency,
        boolean tradable) {

    static MarketInstrumentResponse from(Instrument instrument) {
        return new MarketInstrumentResponse(
                instrument.getSymbol(),
                instrument.getCompanyName(),
                instrument.getExchange(),
                instrument.getInstrumentType(),
                instrument.getCurrency(),
                instrument.isTradable());
    }
}
