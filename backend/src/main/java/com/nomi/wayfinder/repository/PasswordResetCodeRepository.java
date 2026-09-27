package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.entity.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Long> {

    // Only the newest code counts; requesting a new one replaces the old
    Optional<PasswordResetCode> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("DELETE FROM PasswordResetCode c WHERE c.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
