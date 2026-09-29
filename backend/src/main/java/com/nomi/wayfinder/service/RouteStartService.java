package com.nomi.wayfinder.service;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.dto.RouteDtos.StartMode;
import com.nomi.wayfinder.entity.StartKind;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.i18n.Texts;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Where a new route starts ("Rotalarım › Yeni rota"):
 * - LOCATION: the user's position.
 * - AREA: the chosen district's (or city's) best starting point: its most popular sight (Wikipedia popularity) that
 *   is visible, not inside an institution and has at least MIN_FOOD_AROUND cafés / restaurants within
 *   FOOD_RADIUS_METERS (a famous sight in an empty field is a bad start), e.g. Ayasofya for Fatih, Süreyya Operası for
 *   Kadıköy; else the district's / city's centre (OSM label / admin centre).
 * Unknown city / district slugs are 404.
 */
@Service
public class RouteStartService {

    static final int MIN_FOOD_AROUND = 12;
    static final int FOOD_RADIUS_METERS = 800;

    private static final String BEST_SIGHT = """
            SELECT p.name, ST_Y(p.location::geometry) AS lat, ST_X(p.location::geometry) AS lon
            FROM places p
            WHERE %s = ? AND NOT p.hidden AND NOT p.inside_institution AND NOT p.unconfirmed
              AND p.category IN ('ATTRACTION', 'MUSEUM', 'PARK', 'CULTURE')
              AND p.popularity > 0
              AND (SELECT count(*) FROM places q
                   WHERE ST_DWithin(q.location, p.location, ?) AND NOT q.hidden AND NOT q.inside_institution AND NOT q.unconfirmed
                     AND q.category IN ('BREAKFAST', 'RESTAURANT', 'CAFE', 'DESSERT')) >= ?
            ORDER BY p.popularity DESC, p.id
            LIMIT 1
            """;

    private final CityService cityService;
    private final DistrictService districtService;
    private final JdbcTemplate jdbc;

    public RouteStartService(CityService cityService, DistrictService districtService, JdbcTemplate jdbc) {
        this.cityService = cityService;
        this.districtService = districtService;
        this.jdbc = jdbc;
    }

    /**
     * @param label sight / district / city name for AREA starts; null for LOCATION
     */
    public record Start(double latitude, double longitude, StartKind kind, String label) {
    }

    public static StartMode mode(RoutePlanRequest request) {
        if (request.startMode() != null) {
            return request.startMode();
        }
        boolean hasPosition = request.latitude() != null && request.longitude() != null;
        boolean hasCity = request.city() != null && !request.city().isBlank();
        return !hasPosition && hasCity ? StartMode.AREA : StartMode.LOCATION;
    }

    public Start resolve(RoutePlanRequest request) {
        StartMode mode = mode(request);
        if (mode == StartMode.LOCATION) {
            if (request.latitude() == null || request.longitude() == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "latitude and longitude are required (or choose a city with startMode AREA)");
            }
            return new Start(request.latitude(), request.longitude(), StartKind.LOCATION, null);
        }
        if (request.city() == null || request.city().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "city is required for startMode AREA");
        }
        CityService.City city = cityService.findBySlug(request.city())
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + request.city()));
        if (request.district() != null && !request.district().isBlank()) {
            DistrictService.District district = districtService.findBySlug(city.id(), request.district())
                    .orElseThrow(() -> new ResourceNotFoundException("District not found: " + request.district()));
            return bestSight("p.district_id", district.id())
                    .orElse(new Start(district.labelLatitude(), district.labelLongitude(), StartKind.DISTRICT,
                            district.name()));
        }
        return bestSight("p.city_id", city.id())
                .orElse(new Start(city.labelLatitude(), city.labelLongitude(), StartKind.CITY, city.name()));
    }

    // Package-private for tests
    Optional<Start> bestSight(String column, long id) {
        List<Start> found = jdbc.query(BEST_SIGHT.formatted(column),
                (rs, i) -> new Start(rs.getDouble("lat"), rs.getDouble("lon"), StartKind.SIGHT, rs.getString("name")),
                id, FOOD_RADIUS_METERS, MIN_FOOD_AROUND);
        return found.stream().findFirst();
    }

    /**
     * The start as the user sees it: "Konumun", "Ayasofya", "Kadıköy merkezi" (English: "Your location",
     * "Central Kadıköy"). null kind = a route created before start areas existed (the user's position).
     */
    public static String label(StartKind kind, String label) {
        if (kind == null || kind == StartKind.LOCATION || label == null) {
            return Texts.t("Konumun", "Your location");
        }
        return switch (kind) {
            case SIGHT -> label;
            case DISTRICT, CITY -> Texts.t(label + " merkezi", "Central " + label);
            default -> label;
        };
    }
}
