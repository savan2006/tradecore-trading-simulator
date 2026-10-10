package com.tradecore.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderEventRepository extends JpaRepository<OrderEvent, UUID> {
    long countByOrder_Id(UUID orderId);
    java.util.List<OrderEvent> findByOrder_IdOrderByOccurredAtAscIdAsc(UUID orderId);
}
