package com.nomi.wayfinder.service;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.dto.PlaceImage;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularPlace;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteResponse;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularRouteStartRequest;
import com.nomi.wayfinder.dto.PopularRouteDtos.PopularStop;
import com.nomi.wayfinder.dto.RouteDtos.RouteResponse;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.osm.OsmImportFinishedEvent;
import com.nomi.wayfinder.osm.PlacesChangedEvent;
import com.nomi.wayfinder.planning.PlanResult;
import com.nomi.wayfinder.planning.PlannedStop;
import com.nomi.wayfinder.planning.PlanningRequest;
import com.nomi.wayfinder.planning.PopularRouteBuilder;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Area;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Cluster;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Itinerary;
import com.nomi.wayfinder.planning.PopularRouteBuilder.PlannedStopRef;
import com.nomi.wayfinder.planning.PopularRouteBuilder.Sight;
import com.nomi.wayfinder.planning.RoutePlanner;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.repository.PopularSightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Popüler rotalar" of a city or district: the routes people really walk. Nobody spends a day on "only historic
 * places" or "only parks"; they visit the famous places that are near each other, in order, and eat in between.
 * So each route is one walkable group of the area's most popular sights (Wikipedia sitelinks + pageviews, see
 * popularity/PlacePopularity) in walking order, with lunch / coffee / dessert chosen by the real RoutePlanner
 * (open at that time, same side of the Bosphorus, scored like any plan) - see planning/PopularRouteBuilder.
 * From 09:30, one person, no budget, MEDIUM walking, today's opening hours and weather. At most MAX_ROUTES routes;
 * a route needs PopularRouteBuilder.MIN_SIGHTS real sights and at most MAX_WALKING_MINUTES of walking.
 * Nothing is saved; POST /routes/popular/start saves exactly the previewed route for the user (RouteService).
 */
@Service
public class PopularRouteService {

    public static final String CACHE = "popularRoutes2";
    static final int MAX_ROUTES = 5;
    static final int MAX_CLUSTERS_TRIED = 15;
    static final int MAX_WALKING_MINUTES = 90;
    // "Az yürüyelim": a popular route that walks at most this much in total counts as little walking
    static final int LESS_WALKING_MAX_MINUTES = 40;
    static final int CITY_SEEDS = 250;
    static final int DISTRICT_SEEDS = 120;

    private static final Logger log = LoggerFactory.getLogger(PopularRouteService.class);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final RoutePlanner planner;
    private final CityService cityService;
    private final DistrictService districtService;
    private final RouteService routeService;
    private final PlaceRepository placeRepository;
    private final PopularSightRepository sightRepository;
    private final Clock clock;
    // Through the proxy, so start() reuses the cached preview
    private final PopularRouteService self;

    public PopularRouteService(RoutePlanner planner, CityService cityService, DistrictService districtService,
                               RouteService routeService, PlaceRepository placeRepository, PopularSightRepository sightRepository,
                               Clock clock, @Lazy PopularRouteService self) {
        this.planner = planner;
        this.cityService = cityService;
        this.districtService = districtService;
        this.routeService = routeService;
        this.placeRepository = placeRepository;
        this.sightRepository = sightRepository;
        this.clock = clock;
        this.self = self;
    }

    /**
     * Saves a previewed popular route for the user: the same stops, times and lengths (every stop pinned);
     * the planner only replaces a stop that became impossible (closed, rain on an outdoor place).
     * 404 when the key is not among the area's popular routes for that day.
     */
    @Transactional
    public RouteResponse start(Long userId, PopularRouteStartRequest request) {
        LocalDate date = dayOrToday(request.date());
        PopularRouteService routes = self != null ? self : this;
        PopularRouteResponse route = routes.popularRoutes(request.city(), request.district(), date).stream()
                .filter(r -> r.key().equals(request.key()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Popular route not found: " + request.key()));
        return routeService.createPlannedRoute(userId, route.title(), replayRequest(route), route.startLabel());
    }

    /**
     * The assistant's "ünlü bir rota": the area's best popular route (with little walking, the one that walks least),
     * saved for the user with their group size and budget. Empty when the area has none (no famous sights known or
     * the area is unknown).
     */
    @Transactional
    public Optional<RouteResponse> startBest(Long userId, String city, String district, LocalDate date,
                                             Integer partySize, Integer budget, boolean lessWalking) {
        if (city == null || city.isBlank()) {
            return Optional.empty();
        }
        LocalDate day = dayOrToday(date);
        PopularRouteService routes = self != null ? self : this;
        List<PopularRouteResponse> found;
        try {
            found = routes.popularRoutes(city, district, day);
        } catch (ResourceNotFoundException | BusinessException e) {
            return Optional.empty();
        }
        // The routes come most popular first. Little walking: the most popular one that walks little enough, else
        // the one that walks least (not simply the least walking: that was a town 80 km from Bursa)
        Optional<PopularRouteResponse> best = lessWalking
                ? found.stream().filter(r -> r.totalWalkingMinutes() <= LESS_WALKING_MAX_MINUTES).findFirst()
                .or(() -> found.stream().min(Comparator.comparingInt(PopularRouteResponse::totalWalkingMinutes)))
                : found.stream().findFirst();
        return best.map(route -> {
            PlanningRequest replay = replayRequest(route);
            PlanningRequest request = new PlanningRequest(replay.startLatitude(), replay.startLongitude(),
                    replay.date(), replay.startTime(), replay.endTime(),
                    partySize != null ? partySize : replay.partySize(), budget, replay.walkingTolerance(),
                    replay.interests(), replay.slots(), replay.excludedPlaceIds(), replay.assumeWet());
            return routeService.createPlannedRoute(userId, route.title(), request, route.startLabel());
        });
    }

    // The planner input that reproduces a previewed route
    static PlanningRequest replayRequest(PopularRouteResponse route) {
        List<PlannedStopRef> stops = route.stops().stream()
                .map(s -> new PlannedStopRef(s.type(), s.place().id(), LocalTime.parse(s.time(), HH_MM),
                        s.durationMinutes()))
                .toList();
        return PopularRouteBuilder.replay(stops, route.startLatitude(), route.startLongitude(), route.date());
    }

    /**
     * @param date must not be null (the controller fills in today, so a cached "today" never outlives its day)
     */
    @Cacheable(cacheNames = CACHE,
            key = "#city.trim().toLowerCase(T(java.util.Locale).ROOT) + ':' + (#district == null ? '' : #district.trim().toLowerCase(T(java.util.Locale).ROOT)) + ':' + #date + ':' + T(com.nomi.wayfinder.i18n.Texts).english()")
    @Transactional(readOnly = true)
    public List<PopularRouteResponse> popularRoutes(String city, String district, LocalDate date) {
        requireNotPast(date);
        Scope scope = resolveScope(city, district);

        List<Sight> seeds = seeds(scope);
        List<Cluster> clusters = PopularRouteBuilder.clusters(seeds);
        List<PopularRouteResponse> routes = new ArrayList<>();
        Set<String> usedTitles = new HashSet<>();
        for (Cluster cluster : clusters.subList(0, Math.min(MAX_CLUSTERS_TRIED, clusters.size()))) {
            if (routes.size() >= MAX_ROUTES) {
                break;
            }
            Optional<Itinerary> itinerary = PopularRouteBuilder.itinerary(cluster, date);
            if (itinerary.isEmpty()) {
                continue;
            }
            PlanResult result = planner.plan(PopularRouteBuilder.request(itinerary.get(), date));
            // The planner replaces a pinned sight that turned out impossible (closed, outdoors in the rain); the
            // route is only "popular" while at least MIN_SIGHTS of the group's own sights are still in it
            Set<Long> popularIds = new HashSet<>();
            itinerary.get().sights().forEach(s -> popularIds.add(s.place().getId()));
            long kept = result.stops().stream()
                    .filter(s -> s.type() == StopType.SIGHTSEEING && popularIds.contains(s.place().getId()))
                    .count();
            int walking = result.stops().stream().mapToInt(PlannedStop::walkingMinutes).sum();
            if (kept < PopularRouteBuilder.MIN_SIGHTS || walking > MAX_WALKING_MINUTES) {
                log.debug("Popular routes {}/{}: group of {} skipped ({} of its sights kept, {} min walking)", city,
                        district, cluster.centre().place().getName(), kept, walking);
                continue;
            }
            String title = title(scope, itinerary.get().sights(), usedTitles);
            usedTitles.add(title);
            routes.add(toResponse(PopularRouteBuilder.key(cluster, itinerary.get()), title, date, result,
                    itinerary.get().sights()));
        }
        return routes;
    }

    // Plans are cached for hours; after an import, a cleanup or new popularity data the area may look different
    @EventListener
    @CacheEvict(cacheNames = CACHE, allEntries = true)
    public void onImport(OsmImportFinishedEvent event) {
        log.debug("Popular routes cache cleared after the import of {}", event.result() == null ? "?" : event.result().city());
    }

    @EventListener
    @CacheEvict(cacheNames = CACHE, allEntries = true)
    public void onPlacesChanged(PlacesChangedEvent event) {
        log.debug("Popular routes cache cleared ({})", event.reason());
    }

    List<Sight> seeds(Scope scope) {
        Map<Long, Double> scores = sightRepository.seedScores(scope.cityId(), scope.districtId(),
                scope.districtId() == null ? CITY_SEEDS : DISTRICT_SEEDS);
        if (scores.isEmpty()) {
            return List.of();
        }
        Map<Long, Place> places = placeRepository.findByIdIn(scores.keySet()).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));
        return scores.entrySet().stream()
                .filter(e -> places.containsKey(e.getKey()))
                .map(e -> new Sight(places.get(e.getKey()), e.getValue()))
                .toList();
    }

    // "Sultanahmet ve çevresi", "Galata–Karaköy"; the district, else the first sight when no area name is near
    String title(Scope scope, List<Sight> sights, Set<String> used) {
        double south = sights.stream().mapToDouble(s -> s.place().getLatitude()).min().orElse(0) - 0.012;
        double north = sights.stream().mapToDouble(s -> s.place().getLatitude()).max().orElse(0) + 0.012;
        double west = sights.stream().mapToDouble(s -> s.place().getLongitude()).min().orElse(0) - 0.016;
        double east = sights.stream().mapToDouble(s -> s.place().getLongitude()).max().orElse(0) + 0.016;
        List<Area> areas = sightRepository.areas(scope.cityId(), south, west, north, east);
        // Places in and just around the group (the area box is wider)
        List<String> nearbyNames = sightRepository.placeNames(south + 0.006, west + 0.008, north - 0.006, east - 0.008);

        Set<String> usedAreas = new HashSet<>();
        used.forEach(t -> usedAreas.add(t.replace(" ve çevresi", "").replace(" and around", "")));
        String area = PopularRouteBuilder.areaTitle(sights, areas, nearbyNames, usedAreas);
        if (area == null) {
            area = sights.stream().map(s -> s.place().getDistrictName()).filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                    .orElse(sights.getFirst().place().getName());
        }
        if (area.contains("–")) {
            return area;
        }
        return Texts.t(area + " ve çevresi", area + " and around");
    }

    /**
     * The city (and district) the routes are for; 404 for an unknown city or a district that is not in the city.
     */
    Scope resolveScope(String citySlug, String districtSlug) {
        CityService.City city = cityService.findBySlug(citySlug)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + citySlug));
        if (districtSlug == null || districtSlug.isBlank()) {
            return new Scope(city.id(), null);
        }
        long districtId = districtService.findIdBySlug(city.id(), districtSlug)
                .orElseThrow(() -> new ResourceNotFoundException("District not found: " + districtSlug));
        return new Scope(city.id(), districtId);
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

    static PopularRouteResponse toResponse(String key, String title, LocalDate date, PlanResult result,
                                           List<Sight> seeds) {
        List<PopularStop> stops = result.stops().stream().map(PopularRouteService::toStop).toList();
        List<Integer> knownPrices = result.stops().stream()
                .map(s -> s.place().getEstimatedCost())
                .filter(Objects::nonNull)
                .toList();
        Integer cost = knownPrices.isEmpty() ? null : knownPrices.stream().mapToInt(Integer::intValue).sum();
        Place first = result.stops().getFirst().place();
        return new PopularRouteResponse(
                key,
                title,
                Texts.t("Bu bölgenin en çok ilgi gören yerleri, yürüme sırasıyla.",
                        "The most popular places of this area, in walking order."),
                popularityNote(seeds),
                first.getName(),
                first.getLatitude(),
                first.getLongitude(),
                date,
                stops,
                result.stops().stream().mapToInt(PlannedStop::walkingMinutes).sum(),
                cost,
                result.stops().size() - knownPrices.size());
    }

    // Why these places count as popular: Wikipedia data for most sights, else our own verified / rated / photo data
    static String popularityNote(List<Sight> sights) {
        long withWikipedia = sights.stream()
                .filter(s -> s.place().getPopularity() != null && s.place().getPopularity() > 0).count();
        return withWikipedia * 2 >= sights.size()
                ? Texts.t("Wikipedia’da en çok okunan yerler", "The most-read places on Wikipedia")
                : Texts.t("Nomi’de doğrulanmış, puanlı ya da fotoğraflı yerler",
                "Places verified, rated or photographed on Nomi");
    }

    private static PopularStop toStop(PlannedStop stop) {
        Place place = stop.place();
        int minutes = (int) Duration.between(stop.start(), stop.end()).toMinutes();
        return new PopularStop(
                stop.start().format(HH_MM),
                minutes < 0 ? minutes + 1440 : minutes,
                stop.type(),
                stop.type().getLabel(),
                stop.walkingMinutes(),
                stop.distanceFromPreviousMeters(),
                new PopularPlace(
                        place.getId(),
                        place.getDisplayName(),
                        place.getCategory(),
                        place.getLatitude(),
                        place.getLongitude(),
                        PlaceImage.of(place),
                        place.getEstimatedCost(),
                        place.isVerified(),
                        place.getDistrictName(),
                        place.getPopularity()));
    }

    /**
     * @param districtId null = the whole city
     */
    record Scope(long cityId, Long districtId) {
    }
}
