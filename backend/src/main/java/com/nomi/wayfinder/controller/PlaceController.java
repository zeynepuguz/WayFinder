package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.PlaceCreateRequest;
import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.service.PlaceService;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    @GetMapping
    public List<PlaceResponse> getAllPlaces() {
        return placeService.getAllPlaces();
    }

    @GetMapping("/{id}")
    public PlaceResponse getPlaceById(@PathVariable Long id) {
        return placeService.getPlaceById(id);
    }

    @PostMapping
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
    public void deletePlace(@PathVariable Long id) {
        placeService.deletePlace(id);
    }


}