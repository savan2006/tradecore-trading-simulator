package com.tradecore.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit opt-in smoke test for two real symbols and the configured PostgreSQL database. */
@SpringBootTest
@EnabledIfSystemProperty(named = "tradecore.market-data.live-refresh-test", matches = "true")
class MarketDataRefreshLiveVerificationTest {

    private static final List<String> SYMBOLS = List.of("TCS", "TRENT");

    @Autowired
    private MarketDataIngestionService ingestionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void performsTwoRealQuoteRefreshesWithoutCreatingDuplicateRows() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName())
                    .as("live refresh verification must use PostgreSQL")
                    .containsIgnoringCase("PostgreSQL");
        }

        int rowsBefore = countQuoteRows();
        assertThat(rowsBefore).isEqualTo(SYMBOLS.size());
        Instant firstStartedAt = Instant.now();
        MarketDataIngestionResult first = ingestionService.ingestCurrentQuotes(SYMBOLS);
        int rowsAfterFirst = countQuoteRows();
        List<String> idsAfterFirst = quoteIds();
        assertThat(first.inserted()).isZero();
        assertThat(first.updated()).isEqualTo(SYMBOLS.size());
        assertThat(rowsAfterFirst).isEqualTo(SYMBOLS.size());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM market_quote q
                JOIN instrument i ON i.id = q.instrument_id
                WHERE i.symbol IN ('TCS', 'TRENT') AND q.last_price IS NOT NULL
                """, Integer.class)).isEqualTo(SYMBOLS.size());

        Instant secondStartedAt = Instant.now();
        MarketDataIngestionResult second = ingestionService.ingestCurrentQuotes(SYMBOLS);

        assertThat(second.inserted()).isZero();
        assertThat(second.updated()).isEqualTo(SYMBOLS.size());
        assertThat(countQuoteRows()).isEqualTo(rowsAfterFirst);
        assertThat(quoteIds()).containsExactlyElementsOf(idsAfterFirst);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM market_quote q
                JOIN instrument i ON i.id = q.instrument_id
                WHERE i.symbol IN ('TCS', 'TRENT') AND q.received_at >= ?
                """, Integer.class, Timestamp.from(secondStartedAt))).isEqualTo(SYMBOLS.size());

        System.out.printf("LIVE_NSE_POSTGRES_REFRESH symbols=%s rowsBefore=%d firstInserted=%d firstUpdated=%d "
                        + "secondInserted=%d secondUpdated=%d persistedRows=%d firstStartedAt=%s%n",
                String.join(",", SYMBOLS), rowsBefore, first.inserted(), first.updated(),
                second.inserted(), second.updated(), countQuoteRows(), firstStartedAt);
    }

    private int countQuoteRows() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM market_quote q
                JOIN instrument i ON i.id = q.instrument_id
                WHERE i.symbol IN ('TCS', 'TRENT')
                """, Integer.class);
    }

    private List<String> quoteIds() {
        return jdbcTemplate.queryForList("""
                SELECT CAST(q.id AS VARCHAR) FROM market_quote q
                JOIN instrument i ON i.id = q.instrument_id
                WHERE i.symbol IN ('TCS', 'TRENT') ORDER BY i.symbol
                """, String.class);
    }
}
