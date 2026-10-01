package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.NearbyPlaceResponse;
import com.nomi.wayfinder.dto.PageResponse;
import com.nomi.wayfinder.dto.PlaceCreateRequest;
import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.service.PlaceService;
import com.nomi.wayfinder.service.PlaceService.PlaceSearchFilter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    @GetMapping
    public PageResponse<PlaceResponse> searchPlaces(
            @RequestParam(required = false) PlaceCategory category,
            @RequestParam(required = false) String neighborhood,
            @RequestParam(required = false) @PositiveOrZero Integer maxCost,
            @RequestParam(required = false) Boolean indoor,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean verified,
            // City slug from GET /api/v1/cities; the list is then verified first, then with a photo, then by name
            @RequestParam(required = false) String city,
            // District slug from GET /api/v1/districts?city=, looked up in the city (istanbul when no city is given)
            @RequestParam(required = false) String district,
            // A sub-kind: İbadet > mosque / church / synagogue / cemevi
            @RequestParam(required = false) @Size(max = 30) String tag,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size
    ) {
        return placeService.searchPlaces(
                new PlaceSearchFilter(category, neighborhood, maxCost, indoor, q, verified, district, city, tag), page, size);
    }

    // lat/lon = user's current location (from device GPS), not a place's
    @GetMapping("/nearby")
    public List<NearbyPlaceResponse> getNearbyPlaces(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon,
            @RequestParam(defaultValue = "1000") @Positive @Max(5000) double radius,
            // Filter before the limit: otherwise 50 nearby cafes could hide every museum
            @RequestParam(required = false) PlaceCategory category,
            @RequestParam(required = false) @Size(max = 30) String tag,
            @RequestParam(defaultValue = "20") @Positive @Max(50) int limit
    ) {
        return placeService.getNearbyPlaces(lat, lon, radius, category, tag, limit);
    }

    /**
     * Live map: places inside the visible box (max 0.6° per side, else 400).
     * lat/lon (optional) = the user's location; distances and ordering use it, else the box center.
     */
    @GetMapping("/in-area")
    public List<NearbyPlaceResponse> getPlacesInArea(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double south,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double west,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double north,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double east,
            @RequestParam(required = false) @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @RequestParam(required = false) @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
            @RequestParam(required = false) PlaceCategory category,
            @RequestParam(required = false) @Size(max = 30) String tag,
            @RequestParam(defaultValue = "200") @Positive @Max(300) int limit
    ) {
        return placeService.getPlacesInArea(south, west, north, east, lat, lon, category, tag, limit);
    }

    @GetMapping("/{id}")
    public PlaceResponse getPlaceById(@PathVariable Long id) {
        return placeService.getPlaceById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlaceResponse createPlace(
            @Valid @RequestBody PlaceCreateRequest request)
    {
        return placeService.createPlace(request);
    }

    @PutMapping("/{id}")
    public PlaceResponse updatePlace(
            @PathVariable Long id,
            @Valid @RequestBody PlaceCreateRequest request
    ) {
        return placeService.updatePlace(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePlace(@PathVariable Long id) {
        placeService.deletePlace(id);
    }


}
