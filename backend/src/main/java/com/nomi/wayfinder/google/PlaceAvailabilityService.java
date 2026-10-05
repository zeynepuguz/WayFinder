package com.nomi.wayfinder.google;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.geo.GeoMath;
import com.nomi.wayfinder.google.GooglePlacesClient.GooglePlace;
import com.nomi.wayfinder.osm.OsmPlaceMapper;
import com.nomi.wayfinder.osm.PlaceRealismFilter;
import com.nomi.wayfinder.osm.PlacesChangedEvent;
import com.nomi.wayfinder.repository.PlaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * "Is this place still there?" for the place page, asked of Google when a Places API key is set. Open data keeps
 * closed places for years ("Seyir Cafe", "Akdağ Çayevi" are closed on Google but open in OSM / Overture), and a
 * search by name alone opens another place with the same name. So: the Google place of this name within
 * radius-meters of our pin decides; a permanently closed one is hidden from Nomi; no match says it may be closed;
 * an open one is opened in Google Maps by its own place id (never a namesake).
 */
@Service
public class PlaceAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(PlaceAvailabilityService.class);

    public enum Status {
        // Google knows it here and it is open
        OPEN,
        CLOSED_TEMPORARILY,
        // Google says it closed for good: hidden from Nomi from now on
        CLOSED_PERMANENTLY,
        // Google knows no place of this name here: it may have closed
        NOT_FOUND,
        // Not asked (no API key, or Google did not answer)
        UNCHECKED
    }

    /**
     * @param mapsUrl    where "Google Haritalar'da aç" goes: the exact Google place, else a search around our pin
     * @param googleName the name Google uses, null when there is no match
     */
    public record Availability(Status status, String mapsUrl, String googleName) {
    }

    private final GooglePlacesClient client;
    private final GooglePlacesProperties properties;
    private final PlaceRepository placeRepository;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final GooglePlacesBudget budget;

    public PlaceAvailabilityService(GooglePlacesClient client, GooglePlacesProperties properties,
                                    PlaceRepository placeRepository, JdbcTemplate jdbc,
                                    ApplicationEventPublisher events, GooglePlacesBudget budget) {
        this.budget = budget;
        this.client = client;
        this.properties = properties;
        this.placeRepository = placeRepository;
        this.jdbc = jdbc;
        this.events = events;
    }

    public Availability check(long placeId) {
        Place place = placeRepository.findById(placeId)
                .orElseThrow(() -> new ResourceNotFoundException("Place not found: " + placeId));
        String nearPin = searchAroundPin(place);
        // No key, or today's / this month's free share is used up: never a billed call
        if (!client.enabled() || !budget.tryAcquire()) {
            return new Availability(Status.UNCHECKED, nearPin, null);
        }
        List<GooglePlace> found;
        try {
            found = client.search(place.getName(), place.getLatitude(), place.getLongitude());
        } catch (RuntimeException e) {
            log.warn("Google Places check of place {} failed: {}", placeId, e.getMessage());
            return new Availability(Status.UNCHECKED, nearPin, null);
        }
        Optional<GooglePlace> match = bestMatch(place.getName(), place.getLatitude(), place.getLongitude(), found,
                properties.radiusMeters());
        if (match.isEmpty()) {
            return new Availability(Status.NOT_FOUND, nearPin, null);
        }
        GooglePlace google = match.get();
        String exact = "https://www.google.com/maps/search/?api=1&query=" + encode(google.name())
                + "&query_place_id=" + encode(google.id());
        Status status = switch (google.status() == null ? "" : google.status()) {
            case "CLOSED_PERMANENTLY" -> Status.CLOSED_PERMANENTLY;
            case "CLOSED_TEMPORARILY" -> Status.CLOSED_TEMPORARILY;
            default -> Status.OPEN;
        };
        if (status == Status.CLOSED_PERMANENTLY) {
            hide(place);
        }
        return new Availability(status, exact, google.name());
    }

    /**
     * Our place among Google's answers: within the radius of our pin and with the same name (case, Turkish letters
     * and punctuation aside; one name containing the other, or a shared distinctive word), the nearest one.
     */
    static Optional<GooglePlace> bestMatch(String name, double latitude, double longitude, List<GooglePlace> found,
                                           int radiusMeters) {
        return found.stream()
                .filter(g -> GeoMath.meters(latitude, longitude, g.latitude(), g.longitude()) <= radiusMeters)
                .filter(g -> sameName(name, g.name()))
                .min(Comparator.comparingDouble(g -> GeoMath.meters(latitude, longitude, g.latitude(),
                        g.longitude())));
    }

    static boolean sameName(String ours, String theirs) {
        if (ours == null || theirs == null) {
            return false;
        }
        String a = OsmPlaceMapper.fold(ours);
        String b = OsmPlaceMapper.fold(theirs);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.contains(b) || b.contains(a)) {
            return true;
        }
        List<String> theirWords = PlaceRealismFilter.words(theirs);
        return PlaceRealismFilter.words(ours).stream()
                .filter(w -> w.length() >= 4 && !GENERIC_WORDS.contains(w))
                .anyMatch(theirWords::contains);
    }

    // Words every second place has: sharing one of these says nothing
    private static final List<String> GENERIC_WORDS = List.of("cafe", "kafe", "restoran", "restaurant", "lokanta",
            "kahve", "coffee", "market", "pastane", "pastanesi", "firin", "firini", "evi", "bahcesi", "camii", "cami");

    // A Google Maps search for "<city> <name>" around our pin: the nearest of namesakes comes first
    static String searchAroundPin(Place place) {
        String city = place.getCityName();
        boolean named = city == null || OsmPlaceMapper.fold(place.getName()).contains(OsmPlaceMapper.fold(city));
        String query = named ? place.getName() : city + " " + place.getName();
        return "https://www.google.com/maps/search/" + encode(query) + "/@"
                + String.format(Locale.ROOT, "%.6f,%.6f,17z", place.getLatitude(), place.getLongitude());
    }

    private void hide(Place place) {
        int changed = jdbc.update("""
                UPDATE places SET hidden = TRUE, updated_at = now()
                WHERE id = ? AND NOT hidden AND source IN ('OSM', 'OVERTURE')
                """, place.getId());
        if (changed > 0) {
            log.info("Place {} ({}) is permanently closed on Google: hidden", place.getId(), place.getName());
            events.publishEvent(new PlacesChangedEvent("closed on Google"));
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }
}
