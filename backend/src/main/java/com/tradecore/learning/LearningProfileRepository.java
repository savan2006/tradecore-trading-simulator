package com.tradecore.learning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningProfileRepository extends JpaRepository<LearningProfile, UUID> {
    @EntityGraph(attributePaths = "instrument")
    @Query("select p from LearningProfile p where p.instrument.exchange = 'NSE' order by p.instrument.symbol asc")
    List<LearningProfile> findNseProfiles();

    @EntityGraph(attributePaths = "instrument")
    @Query("select p from LearningProfile p where p.instrument.exchange = 'NSE' and p.instrument.symbol = :symbol")
    Optional<LearningProfile> findNseProfileBySymbol(@Param("symbol") String symbol);
}
