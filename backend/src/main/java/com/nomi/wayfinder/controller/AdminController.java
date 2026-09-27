package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.osm.OsmPlaceImporter;
import com.nomi.wayfinder.osm.OsmPlaceImporter.ImportResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Admin-only operations (SecurityConfig: /api/v1/admin/** needs ROLE_ADMIN)
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final OsmPlaceImporter osmPlaceImporter;

    public AdminController(OsmPlaceImporter osmPlaceImporter) {
        this.osmPlaceImporter = osmPlaceImporter;
    }

    // Downloads Istanbul places from OpenStreetMap and upserts them. Takes a few minutes; 409 if one is running
    @PostMapping("/places/import-osm")
    public ImportResult importOsmPlaces() {
        return osmPlaceImporter.importIstanbul();
    }
}
