package com.tradecore.market;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

public interface MarketQuoteRepository extends JpaRepository<MarketQuote, UUID> {
    @EntityGraph(attributePaths = "instrument")
    Optional<MarketQuote> findByInstrument_Id(UUID instrumentId);

    @EntityGraph(attributePaths = "instrument")
    List<MarketQuote> findAllByInstrument_IdIn(Collection<UUID> instrumentIds);

    @EntityGraph(attributePaths = "instrument")
    List<MarketQuote> findAllByInstrument_ExchangeAndInstrument_SymbolIn(String exchange, Collection<String> symbols);

    @Query("select max(q.receivedAt) from MarketQuote q")
    Optional<Instant> findLatestReceivedAt();
}
