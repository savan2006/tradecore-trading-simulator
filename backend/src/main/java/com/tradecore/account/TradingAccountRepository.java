package com.tradecore.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface TradingAccountRepository extends JpaRepository<TradingAccount, UUID> {
    long countByStatus(String status);
    Optional<TradingAccount> findByUser_Id(UUID userId);
    Optional<TradingAccount> findByUser_Email(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from TradingAccount a where a.id = :accountId")
    Optional<TradingAccount> findByIdForUpdate(@Param("accountId") UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from TradingAccount a where a.user.id = :userId")
    Optional<TradingAccount> findByUserIdForUpdate(@Param("userId") UUID userId);
}
