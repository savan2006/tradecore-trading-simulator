package com.tradecore.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit, opt-in end-to-end check against the configured PostgreSQL and live NSE MCP. */
@SpringBootTest
@EnabledIfSystemProperty(named = "tradecore.market-data.live-postgres-smoke", matches = "true")
class MarketDataLivePostgresIngestionSmokeTest {

    private static final List<String> SYMBOLS = List.of("TCS", "TRENT");

    @Autowired
    private MarketDataIngestionService ingestionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void persistsLiveQuotesAndBoundedDailyCandlesIdempotently() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName())
                    .as("live ingestion smoke test must use PostgreSQL")
                    .containsIgnoringCase("PostgreSQL");
        }

        MarketDataIngestionResult firstQuotes = ingestionService.ingestCurrentQuotes(SYMBOLS);
        MarketDataIngestionResult firstCandles = ingestionService.ingestRecentDailyCandles(SYMBOLS);
        int quoteRowsAfterFirst = countQuotes();
        int candleRowsAfterFirst = countCandles();
        assertThat(quoteRowsAfterFirst).isEqualTo(SYMBOLS.size());
        assertThat(candleRowsAfterFirst).isPositive();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM market_quote q
                JOIN instrument i ON i.id = q.instrument_id
                WHERE i.symbol IN ('TCS', 'TRENT')
                  AND q.received_at IS NOT NULL
                  AND q.market_status = 'UNKNOWN'
                """, Integer.class)).isEqualTo(SYMBOLS.size());

        MarketDataIngestionResult secondQuotes = ingestionService.ingestCurrentQuotes(SYMBOLS);
        MarketDataIngestionResult secondCandles = ingestionService.ingestRecentDailyCandles(SYMBOLS);
        assertThat(countQuotes()).isEqualTo(quoteRowsAfterFirst);
        assertThat(countCandles()).isEqualTo(candleRowsAfterFirst);
        assertThat(secondQuotes.inserted()).isZero();
        assertThat(secondQuotes.updated()).isEqualTo(SYMBOLS.size());
        assertThat(secondCandles.inserted()).isZero();
        assertThat(secondCandles.skipped()).isEqualTo(firstCandles.received());

        System.out.printf("LIVE_POSTGRES_NSE_SMOKE symbols=%s quotes_first=%d/%d quotes_second=%d/%d "
                        + "candles_first=%d candles_second_inserted=%d quotes_rows=%d candle_rows=%d%n",
                String.join(",", SYMBOLS), firstQuotes.inserted(), firstQuotes.updated(),
                secondQuotes.inserted(), secondQuotes.updated(), firstCandles.inserted(),
                secondCandles.inserted(), quoteRowsAfterFirst, candleRowsAfterFirst);
    }

    private int countQuotes() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM market_quote q
                JOIN instrument i ON i.id = q.instrument_id
                WHERE i.symbol IN ('TCS', 'TRENT')
                """, Integer.class);
    }

    private int countCandles() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM market_candle c
                JOIN instrument i ON i.id = c.instrument_id
                WHERE i.symbol IN ('TCS', 'TRENT') AND c.resolution = '1D'
                """, Integer.class);
    }
}
