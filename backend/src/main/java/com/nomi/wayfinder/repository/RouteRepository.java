package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.entity.RouteStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RouteRepository extends JpaRepository<Route, Long> {

    Optional<Route> findByIdAndUserId(Long id, Long userId);

    List<Route> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Route> findByUserIdAndSavedTrueOrderByCreatedAtDesc(Long userId);

    // The route the user is most likely talking about in the assistant
    Optional<Route> findFirstByUserIdAndStatusInOrderByUpdatedAtDesc(Long userId, Collection<RouteStatus> statuses);
}
