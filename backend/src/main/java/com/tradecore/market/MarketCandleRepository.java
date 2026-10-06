package com.tradecore.market;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

public interface MarketCandleRepository extends JpaRepository<MarketCandle, UUID> {

    boolean existsByInstrument_IdAndResolutionAndBucketStart(UUID instrumentId, String resolution, Instant bucketStart);

    List<MarketCandle> findAllByInstrument_IdAndResolutionAndBucketStartGreaterThanEqualAndBucketStartLessThanOrderByBucketStartAsc(
            UUID instrumentId, String resolution, Instant fromInclusive, Instant toExclusive, Pageable pageable);

    List<MarketCandle> findAllByInstrument_IdAndResolutionOrderByBucketStartDesc(
            UUID instrumentId, String resolution, Pageable pageable);
}
