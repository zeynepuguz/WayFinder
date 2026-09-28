package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.dto.DistrictResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/districts")
public class DistrictController {

    private final DistrictService districtService;

    public DistrictController(DistrictService districtService) {
        this.districtService = districtService;
    }

    // A city's districts for the district picker / map (city slug from GET /api/v1/cities; default istanbul);
    // filter places with /api/v1/places?city={city}&district={slug}
    @GetMapping
    public List<DistrictResponse> listDistricts(@RequestParam(required = false) String city) {
        return districtService.listDistricts(city);
    }
}
