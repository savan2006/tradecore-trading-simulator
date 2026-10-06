package com.tradecore.portfolio;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.math.BigDecimal;

public interface PositionRepository extends JpaRepository<Position, UUID> {
    long countByQuantityGreaterThan(long quantity);
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "instrument")
    List<Position> findAllByAccount_IdOrderByInstrument_ExchangeAscInstrument_SymbolAscTradingModeAsc(UUID accountId);

    long countByAccount_IdAndQuantityAndRealizedPnlGreaterThan(UUID accountId, long quantity, BigDecimal threshold);
    long countByAccount_IdAndQuantityAndRealizedPnlLessThan(UUID accountId, long quantity, BigDecimal threshold);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "instrument")
    java.util.Optional<Position> findFirstByAccount_IdAndQuantityOrderByRealizedPnlDesc(UUID accountId, long quantity);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "instrument")
    java.util.Optional<Position> findFirstByAccount_IdAndQuantityOrderByRealizedPnlAsc(UUID accountId, long quantity);

    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "instrument")
    List<Position> findTop30ByAccount_IdAndQuantityOrderByUpdatedAtDesc(UUID accountId, long quantity);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.account.id = :accountId and p.instrument.id = :instrumentId and p.tradingMode = :tradingMode")
    Optional<Position> findForUpdate(@Param("accountId") UUID accountId,
            @Param("instrumentId") UUID instrumentId, @Param("tradingMode") String tradingMode);

    Optional<Position> findByAccount_IdAndInstrument_IdAndTradingMode(UUID accountId, UUID instrumentId,
            String tradingMode);

    @Query("select p.id from Position p where p.tradingMode='INTRADAY' and p.quantity > 0 order by p.id asc")
    List<UUID> findOpenIntradayPositionIds();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p where p.id=:id")
    Optional<Position> findByIdForUpdate(@Param("id") UUID id);
}
