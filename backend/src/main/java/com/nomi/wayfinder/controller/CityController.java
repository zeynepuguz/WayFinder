package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.dto.CityResponse;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cities")
public class CityController {

    private final CityService cityService;

    public CityController(CityService cityService) {
        this.cityService = cityService;
    }

    // Turkey's cities for the city picker (placeCount 0 = not imported yet)
    @GetMapping
    public List<CityResponse> listCities() {
        return cityService.listCities();
    }

    // The city the user is in (device GPS); 404 outside every imported city polygon
    @GetMapping("/at")
    public CityResponse cityAt(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lon
    ) {
        return cityService.findAt(lat, lon)
                .orElseThrow(() -> new ResourceNotFoundException("No city at this location"));
    }
}
