package com.tradecore.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.domain.Specification;

public interface TradingOrderRepository extends JpaRepository<TradingOrder, UUID>, JpaSpecificationExecutor<TradingOrder> {
    long countByStatus(String status);
    long countByAccount_Id(UUID accountId);
    long countByAccount_IdAndStatus(UUID accountId, String status);
    long countByAccount_IdAndSide(UUID accountId, String side);
    long countByAccount_IdAndTradingMode(UUID accountId, String tradingMode);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from TradingOrder o join fetch o.account join fetch o.instrument where o.id = :id")
    Optional<TradingOrder> findByIdForUpdate(@Param("id") UUID id);

    List<TradingOrder> findTop100ByStatusOrderByCreatedAtAsc(String status);

    @Query("select o.id from TradingOrder o where o.status='PENDING' and o.tradingMode='INTRADAY' order by o.createdAt asc, o.id asc")
    List<UUID> findPendingIntradayOrderIds();

    @Override
    @EntityGraph(attributePaths = "instrument")
    Page<TradingOrder> findAll(Specification<TradingOrder> specification, Pageable pageable);

    @EntityGraph(attributePaths = "instrument")
    Optional<TradingOrder> findByIdAndAccount_User_Email(UUID id, String email);
}
