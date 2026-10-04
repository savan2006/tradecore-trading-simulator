package com.tradecore.market;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {
    Optional<Instrument> findByExchangeAndSymbol(String exchange, String symbol);

    Optional<Instrument> findByProviderInstrumentKey(String providerInstrumentKey);

    List<Instrument> findAllByTradableTrue();
}
