package com.nomi.wayfinder.repository;

import com.nomi.wayfinder.entity.Place;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceRepository extends JpaRepository<Place, Long> {

}