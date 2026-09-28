package com.nomi.wayfinder.service;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.dto.PlaceImage;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularPlace;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteResponse;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteStartRequest;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularStop;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.osm.OsmImportFinishedEvent;
import com.nomi.wayfinder.planning.PlanResult;
import com.nomi.wayfinder.planning.PlannedStop;
import com.nomi.wayfinder.planning.PlanningRequest;
import com.nomi.wayfinder.planning.PopularTheme;
import com.nomi.wayfinder.planning.RoutePlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Popüler rotalar" of a city or district: every PopularTheme planned by the real RoutePlanner from the district's
 * label point (else the city's: its admin centre in OSM), for one person from 10:00, no budget, MEDIUM walking,
 * with opening hours and the day's weather like any plan. Themes with fewer than PopularTheme.MIN_STOPS real stops
 * are left out. Nothing is saved; POST /routes/popular/start saves the same plan for the user (RouteService).
 */
@Service
public class PopularRouteService {

    public static final String CACHE = "popularRoutes";
    private static final Logger log = LoggerFactory.getLogger(PopularRouteService.class);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final RoutePlanner planner;
    private final CityService cityService;
    private final DistrictService districtService;
    private final RouteService routeService;
    private final Clock clock;

    public PopularRouteService(RoutePlanner planner, CityService cityService, DistrictService districtService,
                               RouteService routeService, Clock clock) {
        this.planner = planner;
        this.cityService = cityService;
        this.districtService = districtService;
        this.routeService = routeService;
        this.clock = clock;
    }

    /**
     * Saves a popular route for the user: the same planner request as the preview (same start, day, theme, 10:00,
     * one person, no budget, MEDIUM walking). Title: "Üsküdar: Tarihi yerler".
     */
    @Transactional
    public RouteResponse start(Long userId, PopularRouteStartRequest request) {
        LocalDate date = dayOrToday(request.date());
        Start start = resolveStart(request.city(), request.district());
        return routeService.createPlannedRoute(userId, request.theme().titleFor(start.label()),
                request(request.theme(), start, date));
    }

    /**
     * @param date must not be null (the controller fills in today, so a cached "today" never outlives its day)
     */
    @Cacheable(cacheNames = CACHE,
            key = "#city.trim().toLowerCase(T(java.util.Locale).ROOT) + ':' + (#district == null ? '' : #district.trim().toLowerCase(T(java.util.Locale).ROOT)) + ':' + #date + ':' + T(com.nomi.wayfinder.i18n.Texts).english()")
    @Transactional(readOnly = true)
    public List<PopularRouteResponse> popularRoutes(String city, String district, LocalDate date) {
        requireNotPast(date);
        Start start = resolveStart(city, district);

        List<PopularRouteResponse> routes = new ArrayList<>();
        for (PopularTheme theme : PopularTheme.values()) {
            PlanResult result = planner.plan(request(theme, start, date));
            if (result.stops().size() >= PopularTheme.MIN_STOPS) {
                routes.add(toResponse(theme, start, date, result));
            } else {
                log.debug("Popular routes {}/{}: {} has only {} stops", city, district, theme, result.stops().size());
            }
        }
        return routes;
    }

    // Plans are cached for hours; after an import the city may have new places
    @EventListener
    @CacheEvict(cacheNames = CACHE, allEntries = true)
    public void onImport(OsmImportFinishedEvent event) {
        log.debug("Popular routes cache cleared after the import of {}", event.result() == null ? "?" : event.result().city());
    }

    // The planner input for a theme; RouteService uses the very same one when the user starts the route
    public PlanningRequest request(PopularTheme theme, Start start, LocalDate date) {
        return RoutePlanner.themeRequest(theme.slots(), theme.interests(), start.latitude(), start.longitude(), date);
    }

    /**
     * Where the route starts: the district's label point when a district is given, else the city's.
     * 404 for an unknown city or a district that is not in the city.
     */
    public Start resolveStart(String citySlug, String districtSlug) {
        CityService.City city = cityService.findBySlug(citySlug)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + citySlug));
        if (districtSlug == null || districtSlug.isBlank()) {
            return new Start(city.name(), city.labelLatitude(), city.labelLongitude());
        }
        DistrictService.District district = districtService.findBySlug(city.id(), districtSlug)
                .orElseThrow(() -> new ResourceNotFoundException("District not found: " + districtSlug));
        return new Start(district.name(), district.labelLatitude(), district.labelLongitude());
    }

    public LocalDate dayOrToday(LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(clock);
        requireNotPast(day);
        return day;
    }

    private void requireNotPast(LocalDate date) {
        if (date.isBefore(LocalDate.now(clock))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Route date cannot be in the past");
        }
    }

    static PopularRouteResponse toResponse(PopularTheme theme, Start start, LocalDate date, PlanResult result) {
        List<PopularStop> stops = result.stops().stream().map(PopularRouteService::toStop).toList();
        List<Integer> knownPrices = result.stops().stream()
                .map(s -> s.place().getEstimatedCost())
                .filter(java.util.Objects::nonNull)
                .toList();
        Integer cost = knownPrices.isEmpty() ? null : knownPrices.stream().mapToInt(Integer::intValue).sum();
        return new PopularRouteResponse(
                theme,
                theme.title(),
                theme.description(),
                start.label(),
                start.latitude(),
                start.longitude(),
                date,
                stops,
                result.stops().stream().mapToInt(PlannedStop::walkingMinutes).sum(),
                cost,
                result.stops().size() - knownPrices.size());
    }

    private static PopularStop toStop(PlannedStop stop) {
        Place place = stop.place();
        return new PopularStop(
                stop.start().format(HH_MM),
                stop.type(),
                stop.type().getLabel(),
                stop.walkingMinutes(),
                stop.distanceFromPreviousMeters(),
                new PopularPlace(
                        place.getId(),
                        place.getName(),
                        place.getCategory(),
                        place.getLatitude(),
                        place.getLongitude(),
                        PlaceImage.of(place),
                        place.getEstimatedCost(),
                        place.isVerified(),
                        place.getDistrictName()));
    }

    /**
     * @param label the district or city name ("Üsküdar", "Ankara")
     */
    public record Start(String label, double latitude, double longitude) {
    }
}
