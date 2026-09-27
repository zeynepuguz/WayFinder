package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.entity.SavedPlace;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedPlaceRepository extends JpaRepository<SavedPlace, SavedPlace.Key> {

    @EntityGraph(attributePaths = "place")
    List<SavedPlace> findByUserIdOrderByCreatedAtDesc(Long userId);
}
