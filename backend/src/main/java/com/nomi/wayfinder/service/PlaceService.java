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
        Specification<Place> spec = (root, query, cb) -> cb.conjunction();

        if (filter.category() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), filter.category()));
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
                    ? cb.notEqual(root.get("source"), Place.OSM_SOURCE)
                    : cb.equal(root.get("source"), Place.OSM_SOURCE));
        }
        if (filter.indoor() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("indoor"), filter.indoor()));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            String pattern = "%" + filter.query().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
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
    public List<NearbyPlaceResponse> getNearbyPlaces(
            double latitude,
            double longitude,
            double radiusMeters,
            PlaceCategory category,
            int limit
    ) {
        List<PlaceDistance> nearby = placeRepository.findNearby(latitude, longitude, radiusMeters,
                category == null ? null : category.name(), limit);

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
                category == null ? null : category.name(), limit);
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
            String city
    ) {

        public PlaceSearchFilter(PlaceCategory category, String neighborhood, Integer maxCost, Boolean indoor,
                                 String query, Boolean verified) {
            this(category, neighborhood, maxCost, indoor, query, verified, null, null);
        }

        public PlaceSearchFilter(PlaceCategory category, String neighborhood, Integer maxCost, Boolean indoor,
                                 String query, Boolean verified, String district) {
            this(category, neighborhood, maxCost, indoor, query, verified, district, null);
        }
    }
}
