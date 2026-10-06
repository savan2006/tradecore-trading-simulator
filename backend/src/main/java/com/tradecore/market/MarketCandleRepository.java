package com.tradecore.market;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Collection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

public interface MarketCandleRepository extends JpaRepository<MarketCandle, UUID> {

    boolean existsByInstrument_IdAndResolutionAndBucketStart(UUID instrumentId, String resolution, Instant bucketStart);

    List<MarketCandle> findAllByInstrument_IdAndResolutionAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
            UUID instrumentId, String resolution, Instant fromInclusive, Instant toExclusive, Pageable pageable);

    List<MarketCandle> findAllByInstrument_IdAndResolutionOrderByBucketStartDesc(
            UUID instrumentId, String resolution, Pageable pageable);

    @Query("select c from MarketCandle c join fetch c.instrument i "
            + "where i.id in :instrumentIds and c.resolution = :resolution "
            + "and c.bucketStart >= :fromInclusive and c.bucketStart < :toExclusive "
            + "order by i.symbol asc, c.bucketStart asc")
    List<MarketCandle> findComparisonCandles(@Param("instrumentIds") Collection<UUID> instrumentIds,
            @Param("resolution") String resolution, @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive, Pageable pageable);
}
