package com.tradecore.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    @Query("select u from User u where (:term is null or lower(u.email) like lower(concat('%', :term, '%')) "
            + "or lower(u.displayName) like lower(concat('%', :term, '%')))" )
    Page<User> searchAdminUsers(@Param("term") String term, Pageable pageable);
}
