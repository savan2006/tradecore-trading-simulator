package com.tradecore.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RiskLimitRepository extends JpaRepository<RiskLimit, UUID> {
    @EntityGraph(attributePaths = {"account.user", "instrument"})
    @Query("select r from RiskLimit r order by r.createdAt desc, r.id")
    List<RiskLimit> findAllForAdmin();

    @Query("select (count(r) > 0) from RiskLimit r where r.enabled = true "
            + "and r.scope = :scope and r.limitType = :limitType "
            + "and ((:accountId is null and r.account is null) or r.account.id = :accountId) "
            + "and ((:instrumentId is null and r.instrument is null) or r.instrument.id = :instrumentId) "
            + "and (:excludeId is null or r.id <> :excludeId) "
            + "and (:effectiveUntil is null or r.effectiveFrom is null or r.effectiveFrom < :effectiveUntil) "
            + "and (r.effectiveUntil is null or :effectiveFrom is null or r.effectiveUntil > :effectiveFrom)")
    boolean existsOverlappingEnabled(@Param("scope") String scope, @Param("limitType") String limitType,
            @Param("accountId") UUID accountId, @Param("instrumentId") UUID instrumentId,
            @Param("effectiveFrom") Instant effectiveFrom, @Param("effectiveUntil") Instant effectiveUntil,
            @Param("excludeId") UUID excludeId);

    @EntityGraph(attributePaths = {"account", "instrument"})
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RiskLimit r where r.id = :id")
    java.util.Optional<RiskLimit> findByIdForAdminUpdate(@Param("id") UUID id);

    @Query("select r from RiskLimit r left join fetch r.instrument where r.enabled = true "
            + "and (r.scope = 'GLOBAL' or r.scope = 'INSTRUMENT' "
            + "or (r.scope = 'ACCOUNT' and r.account.id = :accountId)) "
            + "and (r.effectiveFrom is null or r.effectiveFrom <= :now) "
            + "and (r.effectiveUntil is null or r.effectiveUntil > :now) "
            + "order by r.scope, r.limitType, r.id")
    List<RiskLimit> findCurrentForAccount(@Param("accountId") UUID accountId, @Param("now") Instant now);

    @Query("select r from RiskLimit r where r.enabled = true "
            + "and (r.scope = 'GLOBAL' or (r.scope = 'ACCOUNT' and r.account.id = :accountId) "
            + "or (r.scope = 'INSTRUMENT' and r.instrument.id = :instrumentId)) "
            + "and (r.effectiveFrom is null or r.effectiveFrom <= :now) "
            + "and (r.effectiveUntil is null or r.effectiveUntil > :now)")
    List<RiskLimit> findApplicable(@Param("accountId") UUID accountId,
            @Param("instrumentId") UUID instrumentId, @Param("now") Instant now);
}
