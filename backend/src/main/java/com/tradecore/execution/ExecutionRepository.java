package com.tradecore.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.UUID;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {
    long countByAccount_Id(UUID accountId);
    long countByOrder_Id(UUID orderId);

    @Query(value = "select e from Execution e join fetch e.instrument join fetch e.order "
            + "where e.account.user.email = :email",
            countQuery = "select count(e) from Execution e where e.account.user.email = :email")
    Page<Execution> findOwnedExecutions(@Param("email") String email, Pageable pageable);
}
