package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.media.WikimediaImageResolver;
import com.nomi.wayfinder.media.WikimediaImageResolver.ResolveResult;
import com.nomi.wayfinder.osm.OsmCityImporter;
import com.nomi.wayfinder.osm.OsmContextImporter;
import com.nomi.wayfinder.osm.OsmImportJobs;
import com.nomi.wayfinder.osm.PlaceDataNormalizer;
import com.nomi.wayfinder.osm.OsmImportJobs.ImportStatus;
import com.nomi.wayfinder.osm.PlaceRealismCleanup;
import com.nomi.wayfinder.overture.OvertureImportJobs;
import com.nomi.wayfinder.overture.OverturePlaceImporter;
import com.nomi.wayfinder.popularity.PlacePopularityService;
import com.nomi.wayfinder.popularity.PopularityJobs;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Admin-only operations (SecurityConfig: /api/v1/admin/** needs ROLE_ADMIN)
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final OsmImportJobs importJobs;
    private final WikimediaImageResolver imageResolver;
    private final PlaceRealismCleanup cleanup;
    private final PlacePopularityService popularityService;
    private final PopularityJobs popularityJobs;
    private final CityService cityService;
    private final OsmCityImporter cityImporter;
    private final OsmContextImporter contextImporter;
    private final PlaceDataNormalizer normalizer;
    private final OvertureImportJobs overtureJobs;

    public AdminController(OsmImportJobs importJobs, WikimediaImageResolver imageResolver, PlaceRealismCleanup cleanup,
                           PlacePopularityService popularityService, PopularityJobs popularityJobs,
                           CityService cityService, OsmCityImporter cityImporter, OsmContextImporter contextImporter,
                           PlaceDataNormalizer normalizer, OvertureImportJobs overtureJobs) {
        this.overtureJobs = overtureJobs;
        this.cityImporter = cityImporter;
        this.contextImporter = contextImporter;
        this.normalizer = normalizer;
        this.importJobs = importJobs;
        this.imageResolver = imageResolver;
        this.cleanup = cleanup;
        this.popularityService = popularityService;
        this.popularityJobs = popularityJobs;
        this.cityService = cityService;
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

    // Removes / hides existing places whose name is not a realistic visitable place (school canteens, ...); 409 if running
    @PostMapping("/places/cleanup")
    public PlaceRealismCleanup.CleanupResult cleanupPlaces() {
        return cleanup.run();
    }

    /**
     * Wikipedia popularity (sitelinks + pageviews) of places with a Wikidata id that were not checked recently.
     * - ?city=istanbul: that city now when no pass is running (takes minutes; 200 with the result), else queued (202)
     * - no city: every city, queued in the background (202)
     * GET /places/popularity-status tells whether a pass is still running.
     */
    @PostMapping("/places/update-popularity")
    public ResponseEntity<?> updatePopularity(@RequestParam(required = false) String city) {
        Long cityId = null;
        if (city != null && !city.isBlank()) {
            cityId = cityService.findBySlug(city)
                    .orElseThrow(() -> new ResourceNotFoundException("City not found: " + city)).id();
            if (!popularityJobs.isBusy()) {
                return ResponseEntity.ok(popularityService.update(cityId));
            }
        }
        popularityJobs.requestPass(cityId, "admin");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(java.util.Map.of("queued", true));
    }

    @GetMapping("/places/popularity-status")
    public java.util.Map<String, Boolean> popularityStatus() {
        return java.util.Map.of("running", popularityJobs.isBusy());
    }
    /**
     * Institution areas (campuses, schools, hospitals, prisons, military / industrial zones) and the sea coastline of
     * one city from Overpass, then the places' inside_institution / near_sea flags (takes a minute or two; 409 when
     * running, 503 when Overpass is unavailable - the previous polygons are kept).
     */
    @PostMapping("/places/import-context")
    public OsmContextImporter.ContextImportResult importContext(@RequestParam String city) {
        OsmCityImporter.CityRow row = cityImporter.findCity(city)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + city));
        return contextImporter.importNow(row.toOsmCity());
    }

    /**
     * Food places from Overture Maps (Foursquare, Meta, Microsoft) next to OSM: ?city=slug imports that city now
     * (~1 min; 503 when Overture is unreachable); without it every city is queued in the background
     * (GET /places/overture-status).
     */
    @PostMapping("/places/import-overture")
    public ResponseEntity<?> importOverture(@RequestParam(required = false) String city) {
        if (city != null && !city.isBlank()) {
            OverturePlaceImporter.ImportResult result = overtureJobs.importNow(city);
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(overtureJobs.enqueueAll());
    }

    @GetMapping("/places/overture-status")
    public OvertureImportJobs.Status overtureStatus() {
        return overtureJobs.status();
    }

    // Recomputes one city's inside_institution / near_sea flags from the polygons / coastline already imported
    @PostMapping("/places/context-flags")
    public OsmContextImporter.Flags recomputeContextFlags(@RequestParam String city) {
        long cityId = cityService.findBySlug(city)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + city)).id();
        return contextImporter.recomputeAndNotify(cityId);
    }

    /**
     * Repairs existing OSM place names (broken casing, quotes), hides / removes names that describe an event or a
     * sentence and adds interest tags derived from names / cuisine. dryRun=true only reports.
     */
    @PostMapping("/places/normalize")
    public PlaceDataNormalizer.NormalizeResult normalizePlaces(@RequestParam(defaultValue = "false") boolean dryRun) {
        return normalizer.run(dryRun);
    }
}
