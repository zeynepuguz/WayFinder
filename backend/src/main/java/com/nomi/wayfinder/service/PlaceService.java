package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.NearbyPlaceResponse;
import com.nomi.wayfinder.dto.PageResponse;
import com.nomi.wayfinder.dto.PlaceCreateRequest;
import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.PlaceNotFoundException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlaceService {

    // Largest map box /api/v1/places/in-area accepts, per side (~65 km north-south)
    static final double MAX_AREA_DEGREES = 0.6;

    private final PlaceRepository placeRepository;
    private final PlaceMapper placeMapper;
    private final DistrictService districtService;
    private final CityService cityService;

    public PlaceService(PlaceRepository placeRepository, PlaceMapper placeMapper, DistrictService districtService,
                        CityService cityService) {
        this.placeRepository = placeRepository;
        this.placeMapper = placeMapper;
        this.districtService = districtService;
        this.cityService = cityService;
    }

    // Explore screen: filter + paginate
    @Transactional(readOnly = true)
    public PageResponse<PlaceResponse> searchPlaces(PlaceSearchFilter filter, int page, int size) {
        // Hidden places (not realistic, kept only because a route / saved place / photo uses them) and OSM food places
        // no current source confirms (likely closed, overture/OverturePlaceImporter) are never listed
        Specification<Place> spec = (root, query, cb) -> cb.and(cb.isFalse(root.get("hidden")),
                cb.isFalse(root.get("unconfirmed")));

        if (filter.category() == PlaceCategory.WORSHIP) {
            // Places to pray at, plus the famous mosques / churches that are sights
            spec = spec.and((root, query, cb) -> cb.or(cb.equal(root.get("category"), PlaceCategory.WORSHIP),
                    cb.like(cb.function("array_to_string", String.class, root.get("tags"), cb.literal(",")),
                            "%religious%")));
        } else if (filter.category() == PlaceCategory.BREAKFAST || filter.category() == PlaceCategory.CAFE) {
            // A café serving breakfast is in both lists (osm/PlaceTags.breakfastAware)
            String crossTag = filter.category() == PlaceCategory.BREAKFAST ? "breakfast" : "cafe";
            spec = spec.and((root, query, cb) -> cb.or(cb.equal(root.get("category"), filter.category()),
                    cb.like(cb.function("array_to_string", String.class, root.get("tags"), cb.literal(",")),
                            "%" + crossTag + "%")));
        } else if (filter.category() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), filter.category()));
        } else {
            // "Tümü": markets and places of worship have their own filter
            spec = spec.and((root, query, cb) -> root.get("category")
                    .in(PlaceCategory.MARKET, PlaceCategory.WORSHIP).not());
        }
        if (filter.neighborhood() != null && !filter.neighborhood().isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(cb.lower(root.get("neighborhood")), filter.neighborhood().toLowerCase(Locale.ROOT)));
        }
        if (filter.maxCost() != null) {
            // Unknown price (NULL) never passes a price limit; 0 = free does
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("estimatedCost"), filter.maxCost()));
        }
        if (filter.verified() != null) {
            spec = spec.and((root, query, cb) -> filter.verified()
                    ? root.get("source").in(Place.OSM_SOURCE, Place.OVERTURE_SOURCE).not()
                    : root.get("source").in(Place.OSM_SOURCE, Place.OVERTURE_SOURCE));
        }
        String tag = blankToNull(filter.tag());
        if (tag != null) {
            // ",mosque,religious," contains ",mosque,": the tag itself, not a longer one
            spec = spec.and((root, query, cb) -> cb.like(cb.concat(cb.concat(",",
                            cb.function("array_to_string", String.class, root.get("tags"), cb.literal(","))), ","),
                    "%," + tag + ",%"));
        }
        if (filter.indoor() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("indoor"), filter.indoor()));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            // The text is searched as typed: % and _ are not wildcards
            String typed = filter.query().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String pattern = "%" + typed + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\'));
        }
        boolean byDistrict = filter.district() != null && !filter.district().isBlank();
        boolean byCity = filter.city() != null && !filter.city().isBlank();
        if (byDistrict || byCity) {
            // A district is looked up inside the city (slugs repeat across cities); Istanbul when no city is given
            long cityId = cityService.requireBySlugOrDefault(filter.city()).id();
            if (byDistrict) {
                Long districtId = districtService.findIdBySlug(cityId, filter.district())
                        .orElseThrow(() -> new ResourceNotFoundException("District not found: " + filter.district()));
                spec = spec.and((root, query, cb) -> cb.equal(root.get("districtId"), districtId));
            } else {
                spec = spec.and((root, query, cb) -> cb.equal(root.get("cityId"), cityId));
            }
        }

        // A city / district list shows its best-documented places first: verified, then with a photo
        Sort sort = byDistrict || byCity
                ? Sort.by(Sort.Order.asc("verifiedRank"), Sort.Order.asc("imageRank"), Sort.Order.asc("name"))
                : Sort.by(Sort.Order.desc("rating").nullsLast(), Sort.Order.asc("name"));
        Pageable pageable = PageRequest.of(page, size, sort);

        return PageResponse.of(placeRepository.findAll(spec, pageable).map(placeMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public PlaceResponse getPlaceById(Long id) {
        return placeMapper.toResponse(findPlace(id));
    }

    @Transactional
    public PlaceResponse createPlace(PlaceCreateRequest request) {
        Place place = new Place();
        placeMapper.apply(request, place);
        return placeMapper.toResponse(placeRepository.save(place));
    }

    @Transactional
    public PlaceResponse updatePlace(Long id, PlaceCreateRequest request) {
        Place place = findPlace(id);
        placeMapper.apply(request, place);
        return placeMapper.toResponse(placeRepository.save(place));
    }

    @Transactional
    public void deletePlace(Long id) {
        placeRepository.delete(findPlace(id));
    }

    // Distances and ordering come from PostGIS; here we only load the entities and keep that order
    @Transactional(readOnly = true)
    public List<NearbyPlaceResponse> getNearbyPlaces(double latitude, double longitude, double radiusMeters,
                                                     PlaceCategory category, int limit) {
        return getNearbyPlaces(latitude, longitude, radiusMeters, category, null, limit);
    }

    /**
     * @param tag a sub-kind (İbadet > "mosque", "church", "synagogue", "cemevi"); null = any
     */
    public List<NearbyPlaceResponse> getNearbyPlaces(
            double latitude,
            double longitude,
            double radiusMeters,
            PlaceCategory category,
            String tag,
            int limit
    ) {
        List<PlaceDistance> nearby = placeRepository.findNearby(latitude, longitude, radiusMeters,
                category == null ? null : category.name(), blankToNull(tag), limit);

        Map<Long, Place> places = loadPlaces(nearby);

        return nearby.stream()
                .map(n -> placeMapper.toNearbyResponse(places.get(n.getId()), n.getDistanceMeters()))
                .toList();
    }

    /**
     * Live map: places inside the visible box, verified first, then by distance from (lat, lon)
     * or from the box center when no point is given. Big boxes are rejected: at city scale the
     * limit would return an arbitrary sample, and the query would scan too much.
     */
    @Transactional(readOnly = true)
    public List<NearbyPlaceResponse> getPlacesInArea(
            double south, double west, double north, double east,
            Double latitude, Double longitude,
            PlaceCategory category, int limit
    ) {
        return getPlacesInArea(south, west, north, east, latitude, longitude, category, null, limit);
    }

    public List<NearbyPlaceResponse> getPlacesInArea(
            double south, double west, double north, double east,
            Double latitude, Double longitude,
            PlaceCategory category, String tag, int limit
    ) {
        if (south >= north || west >= east) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "south/west must be smaller than north/east");
        }
        if (north - south > MAX_AREA_DEGREES || east - west > MAX_AREA_DEGREES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "Area too large; zoom in (max " + MAX_AREA_DEGREES + " degrees per side)");
        }
        if ((latitude == null) != (longitude == null)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "lat and lon must be given together");
        }

        double refLat = latitude != null ? latitude : (south + north) / 2;
        double refLon = longitude != null ? longitude : (west + east) / 2;

        List<PlaceDistance> found = placeRepository.findInArea(south, west, north, east, refLat, refLon,
                category == null ? null : category.name(), blankToNull(tag), limit);
        Map<Long, Place> places = loadPlaces(found);

        return found.stream()
                .map(f -> placeMapper.toNearbyResponse(places.get(f.getId()), f.getDistanceMeters()))
                .toList();
    }

    Map<Long, Place> loadPlaces(List<PlaceDistance> distances) {
        List<Long> ids = distances.stream().map(PlaceDistance::getId).toList();
        return placeRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(Place::getId, Function.identity()));
    }

    private Place findPlace(Long id) {
        return placeRepository.findWithOpeningHoursById(id)
                .orElseThrow(() -> new PlaceNotFoundException(id));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public record PlaceSearchFilter(
            PlaceCategory category,
            String neighborhood,
            Integer maxCost,
            Boolean indoor,
            String query,
            // true = only hand-verified places (e.g. for the SEO pages), false = only OSM imports
            Boolean verified,
            // district slug from GET /api/v1/districts?city= ("uskudar"), looked up in the city; null = whole city
            String district,
            // city slug from GET /api/v1/cities ("ankara"); null = no city filter (Istanbul when a district is given)
            String city,
            // a sub-kind tag (İbadet > "mosque", "church", ...); null = any
            String tag
    ) {
    }
}
