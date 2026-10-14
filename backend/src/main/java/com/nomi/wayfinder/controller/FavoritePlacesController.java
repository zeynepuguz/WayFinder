package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.service.PlaceCommunityService;
import com.nomi.wayfinder.service.PlaceMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Favori mekanlar" (Keşfet > Popüler): the places Nomi users saved or rated well, for all Türkiye (no city), a city,
 * or one of its districts. Open to everyone, like the place lists.
 */
@RestController
@Validated
@RequestMapping("/api/v1/places/favorites")
public class FavoritePlacesController {

    private final PlaceCommunityService community;
    private final PlaceRepository places;
    private final PlaceMapper mapper;
    private final CityService cities;
    private final DistrictService districts;

    public FavoritePlacesController(PlaceCommunityService community, PlaceRepository places, PlaceMapper mapper,
                                    CityService cities, DistrictService districts) {
        this.community = community;
        this.places = places;
        this.mapper = mapper;
        this.cities = cities;
        this.districts = districts;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<PlaceResponse> favorites(@RequestParam(required = false) @Size(max = 100) String city,
                                         @RequestParam(required = false) @Size(max = 100) String district,
                                         @RequestParam(defaultValue = "10") @Min(1) @Max(30) int limit) {
        Long cityId = null;
        Long districtId = null;
        if (city != null && !city.isBlank()) {
            cityId = cities.findBySlug(city).orElseThrow(() -> new ResourceNotFoundException("City not found: " + city)).id();
            if (district != null && !district.isBlank()) {
                long inCity = cityId;
                districtId = districts.findIdBySlug(inCity, district)
                        .orElseThrow(() -> new ResourceNotFoundException("District not found: " + district));
            }
        }
        List<Long> ids = community.favoritePlaceIds(cityId, districtId, limit);
        Map<Long, Place> byId = places.findAllById(ids).stream().collect(Collectors.toMap(Place::getId, Function.identity()));
        // In the order of the favourites, not of the database
        return ids.stream().map(byId::get).filter(Objects::nonNull).map(mapper::toResponse).toList();
    }
}
