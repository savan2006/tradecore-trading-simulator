package com.tradecore.market;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface MarketQuoteRepository extends JpaRepository<MarketQuote, UUID> {

    @EntityGraph(attributePaths = "instrument")
    Optional<MarketQuote> findByInstrument_Id(UUID instrumentId);

    @EntityGraph(attributePaths = "instrument")
    List<MarketQuote> findAllByInstrument_IdIn(Collection<UUID> instrumentIds);

    @EntityGraph(attributePaths = "instrument")
    List<MarketQuote> findAllByInstrument_ExchangeAndInstrument_SymbolIn(
            String exchange, Collection<String> symbols);
}
