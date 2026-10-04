package com.tradecore.market;

public class MarketDataProviderException extends RuntimeException {

    public enum Category {
        TIMEOUT,
        PROVIDER_ERROR,
        UNAVAILABLE_DATA,
        MALFORMED_RESPONSE
    }

    private final Category category;

    public MarketDataProviderException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public MarketDataProviderException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
