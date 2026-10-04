package com.tradecore.alert;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceAlertRepository extends JpaRepository<PriceAlert, UUID> {
    List<PriceAlert> findAllByUser_EmailOrderByCreatedAtDescIdDesc(String email);
    boolean existsByWatchlist_IdAndInstrument_IdAndConditionAndTargetPriceAndActiveTrue(
            UUID watchlistId, UUID instrumentId, String condition, java.math.BigDecimal targetPrice);
    @Query("select a.id from PriceAlert a where a.active=true order by a.createdAt asc, a.id asc")
    List<UUID> findAllActiveIds();
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PriceAlert a join fetch a.user join fetch a.watchlist join fetch a.instrument where a.id=:id")
    Optional<PriceAlert> findForUpdate(@Param("id") UUID id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PriceAlert a join fetch a.watchlist join fetch a.instrument where a.id=:id and lower(a.user.email)=:email")
    Optional<PriceAlert> findOwnedForUpdate(@Param("id") UUID id, @Param("email") String email);
}
