package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.media.WikimediaImageResolver;
import com.nomi.wayfinder.media.WikimediaImageResolver.ResolveResult;
import com.nomi.wayfinder.osm.OsmCityImporter;
import com.nomi.wayfinder.osm.OsmImportJobs;
import com.nomi.wayfinder.osm.OsmImportJobs.ImportStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Admin-only operations (SecurityConfig: /api/v1/admin/** needs ROLE_ADMIN)
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final OsmImportJobs importJobs;
    private final WikimediaImageResolver imageResolver;

    public AdminController(OsmImportJobs importJobs, WikimediaImageResolver imageResolver) {
        this.importJobs = importJobs;
        this.imageResolver = imageResolver;
    }

    /**
     * OpenStreetMap import; 409 if one is running.
     * - ?city=ankara: that city's districts, neighbourhoods and places now (takes minutes; 200 with the result)
     * - no city: every city (nomi.osm.cities) one after another in the background; 202 with the job status
     *   (GET /places/import-status). force=true also re-imports cities imported within nomi.osm.refresh-after
     * Place photos are looked up after each city in the background (nomi.images.resolve-after-import).
     */
    @PostMapping("/places/import-osm")
    public ResponseEntity<?> importOsmPlaces(
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        if (city != null && !city.isBlank()) {
            OsmCityImporter.CityImportResult result = importJobs.importOne(city);
            return ResponseEntity.ok(result);
        }
        ImportStatus status = importJobs.startAll("admin", force, false);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
    }

    // Turkey's 81 provinces only (boundaries, label points); 409 if an import is running
    @PostMapping("/places/import-provinces")
    public OsmCityImporter.ProvinceImportResult importProvinces() {
        return importJobs.importProvinces();
    }

    @GetMapping("/places/import-status")
    public ImportStatus importStatus() {
        return importJobs.status();
    }

    // Stops the background import after the city it is working on
    @PostMapping("/places/import-osm/cancel")
    public ImportStatus cancelImport() {
        return importJobs.cancel();
    }

    // Looks up Wikimedia Commons photos for places not checked yet (or due a recheck); 409 if one is running
    @PostMapping("/places/resolve-images")
    public ResolveResult resolvePlaceImages() {
        return imageResolver.resolve();
    }
}
