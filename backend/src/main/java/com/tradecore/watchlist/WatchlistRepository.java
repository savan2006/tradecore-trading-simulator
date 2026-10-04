package com.tradecore.watchlist;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WatchlistRepository extends JpaRepository<Watchlist, UUID> {
    @EntityGraph(attributePaths = "user")
    List<Watchlist> findAllByUser_EmailOrderByCreatedAtDescIdDesc(String email);

    @EntityGraph(attributePaths = "user")
    Optional<Watchlist> findByIdAndUser_Email(UUID id, String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Watchlist w join fetch w.user where w.id=:id and lower(w.user.email)=:email")
    Optional<Watchlist> findOwnedForUpdate(@Param("id") UUID id, @Param("email") String email);
}
