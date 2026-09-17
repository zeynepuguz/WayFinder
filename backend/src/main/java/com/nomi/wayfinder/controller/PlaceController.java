package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.PlaceCreateRequest;
import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.entity.Place;
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

    @PostMapping
    public PlaceResponse createPlace(
            @Valid @RequestBody PlaceCreateRequest request
    ) {
        return placeService.createPlace(request);
    }
}