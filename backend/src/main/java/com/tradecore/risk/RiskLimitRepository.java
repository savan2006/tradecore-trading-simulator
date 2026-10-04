package com.tradecore.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface RiskLimitRepository extends JpaRepository<RiskLimit, UUID> {
    @Query("select r from RiskLimit r where r.enabled = true "
            + "and (r.scope = 'GLOBAL' or (r.scope = 'ACCOUNT' and r.account.id = :accountId) "
            + "or (r.scope = 'INSTRUMENT' and r.instrument.id = :instrumentId)) "
            + "and (r.effectiveFrom is null or r.effectiveFrom <= :now) "
            + "and (r.effectiveUntil is null or r.effectiveUntil > :now)")
    List<RiskLimit> findApplicable(@Param("accountId") UUID accountId,
            @Param("instrumentId") UUID instrumentId, @Param("now") Instant now);
}
