package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.entity.AccessPass;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AccessPassRepository extends JpaRepository<AccessPass, Long> {

    Optional<AccessPass> findByPurchaseToken(String purchaseToken);

    // Passes that are still running or not started yet (stacked), latest end first
    @Query("""
            SELECT p FROM AccessPass p
            WHERE p.userId = :userId AND p.status = com.nomi.wayfinder.entity.AccessPass.Status.ACTIVE
              AND p.expiresAt > :now
            ORDER BY p.expiresAt DESC
            """)
    List<AccessPass> findUnexpired(@Param("userId") Long userId, @Param("now") Instant now);

    @Query("""
            SELECT COUNT(p) > 0 FROM AccessPass p
            WHERE p.userId = :userId AND p.status = com.nomi.wayfinder.entity.AccessPass.Status.ACTIVE
              AND p.startsAt <= :now AND p.expiresAt > :now
            """)
    boolean hasAccessAt(@Param("userId") Long userId, @Param("now") Instant now);
}
