package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.entity.RouteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RouteRepository extends JpaRepository<Route, Long> {

    Optional<Route> findByIdAndUserId(Long id, Long userId);

    Optional<Route> findByShareToken(String shareToken);

    List<Route> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Route> findByUserIdAndSavedTrueOrderByCreatedAtDesc(Long userId);

    // The routes of one day, the one the user is most likely busy with first
    List<Route> findByUserIdAndDateAndStatusInOrderByUpdatedAtDesc(
            Long userId, LocalDate date, Collection<RouteStatus> statuses);

    // Unfinished routes of days that are over -> EXPIRED (all users: the daily job; one user: on read)
    @Modifying
    @Query("UPDATE Route r SET r.status = com.nomi.wayfinder.entity.RouteStatus.EXPIRED "
            + "WHERE r.date < :today AND r.status IN (com.nomi.wayfinder.entity.RouteStatus.DRAFT, "
            + "com.nomi.wayfinder.entity.RouteStatus.ACTIVE)")
    int expireBefore(@Param("today") LocalDate today);

    @Modifying
    @Query("UPDATE Route r SET r.status = com.nomi.wayfinder.entity.RouteStatus.EXPIRED "
            + "WHERE r.userId = :userId AND r.date < :today AND r.status IN ("
            + "com.nomi.wayfinder.entity.RouteStatus.DRAFT, com.nomi.wayfinder.entity.RouteStatus.ACTIVE)")
    int expireBefore(@Param("userId") Long userId, @Param("today") LocalDate today);
}
