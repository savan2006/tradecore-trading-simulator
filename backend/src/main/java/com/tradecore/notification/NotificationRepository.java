package com.tradecore.notification;

import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    long countByReadAtIsNull();
    Page<Notification> findByUser_EmailOrderByCreatedAtDescIdDesc(String email, Pageable pageable);
    Page<Notification> findByUser_EmailAndReadAtIsNullOrderByCreatedAtDescIdDesc(String email, Pageable pageable);
    long countByUser_EmailAndReadAtIsNull(String email);
    java.util.Optional<Notification> findByIdAndUser_Email(UUID id, String email);

    @Modifying
    @Query("update Notification n set n.readAt = :readAt where n.id = :id and n.user.email = :email and n.readAt is null")
    int markReadIfUnread(@Param("id") UUID id, @Param("email") String email, @Param("readAt") Instant readAt);

    @Modifying
    @Query("update Notification n set n.readAt = :readAt where n.user.email = :email and n.readAt is null")
    int markAllRead(@Param("email") String email, @Param("readAt") Instant readAt);
}
