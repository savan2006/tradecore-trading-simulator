package com.tradecore.market;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketSessionRepository extends JpaRepository<MarketSession, UUID> {
    Optional<MarketSession> findByTradingDateAndActiveTrue(LocalDate tradingDate);
    Optional<MarketSession> findByTradingDate(LocalDate tradingDate);
    List<MarketSession> findAllByOrderByTradingDateDesc();
}
