package com.tradecore.journal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeJournalRepository extends JpaRepository<TradeJournalEntry, UUID> {
    boolean existsByOrder_Id(UUID orderId);

    @EntityGraph(attributePaths = {"order", "order.instrument"})
    Optional<TradeJournalEntry> findByIdAndUser_Email(UUID id, String email);

    @EntityGraph(attributePaths = {"order", "order.instrument"})
    Page<TradeJournalEntry> findByUser_Email(String email, Pageable pageable);
}
