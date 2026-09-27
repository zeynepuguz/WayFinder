package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.media.WikimediaImageResolver;
import com.nomi.wayfinder.media.WikimediaImageResolver.ResolveResult;
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
    private final WikimediaImageResolver imageResolver;

    public AdminController(OsmPlaceImporter osmPlaceImporter, WikimediaImageResolver imageResolver) {
        this.osmPlaceImporter = osmPlaceImporter;
        this.imageResolver = imageResolver;
    }

    // Downloads Istanbul places from OpenStreetMap and upserts them. Takes a few minutes; 409 if one is running.
    // Place photos are looked up afterwards in the background (nomi.images.resolve-after-import)
    @PostMapping("/places/import-osm")
    public ImportResult importOsmPlaces() {
        return osmPlaceImporter.importIstanbul();
    }

    // Looks up Wikimedia Commons photos for places not checked yet (or due a recheck); 409 if one is running
    @PostMapping("/places/resolve-images")
    public ResolveResult resolvePlaceImages() {
        return imageResolver.resolve();
    }
}
