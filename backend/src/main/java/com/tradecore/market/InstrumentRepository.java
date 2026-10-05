package com.tradecore.market;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Collection;

public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {
    long countByTradableTrue();
    Optional<Instrument> findByExchangeAndSymbol(String exchange, String symbol);

    Optional<Instrument> findByProviderInstrumentKey(String providerInstrumentKey);

    List<Instrument> findAllByTradableTrue();

    List<Instrument> findAllByExchangeAndTradableTrue(String exchange);

    List<Instrument> findAllByExchangeAndTradableTrueOrderBySymbolAsc(String exchange);

    List<Instrument> findAllByExchangeAndTradableTrueAndSymbolContainingIgnoreCaseOrderBySymbolAsc(
            String exchange, String symbol);

    List<Instrument> findAllByExchangeAndTradableTrueAndSymbolIn(String exchange, Collection<String> symbols);
}
