package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.NearbyPlaceResponse;
import com.nomi.wayfinder.dto.PageResponse;
import com.nomi.wayfinder.dto.PlaceCreateRequest;
import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.exception.PlaceNotFoundException;
import com.nomi.wayfinder.repository.PlaceDistance;
import com.nomi.wayfinder.repository.PlaceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlaceService {

    private final PlaceRepository placeRepository;
    private final PlaceMapper placeMapper;

    public PlaceService(PlaceRepository placeRepository, PlaceMapper placeMapper) {
        this.placeRepository = placeRepository;
        this.placeMapper = placeMapper;
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
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(cb.coalesce(root.get("estimatedCost"), 0), filter.maxCost()));
        }
        if (filter.indoor() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("indoor"), filter.indoor()));
        }
        if (filter.query() != null && !filter.query().isBlank()) {
            String pattern = "%" + filter.query().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern));
        }

        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("rating").nullsLast(), Sort.Order.asc("name")));

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
            int limit
    ) {
        List<PlaceDistance> nearby = placeRepository.findNearby(latitude, longitude, radiusMeters, limit);

        Map<Long, Place> places = loadPlaces(nearby);

        return nearby.stream()
                .map(n -> placeMapper.toNearbyResponse(places.get(n.getId()), n.getDistanceMeters()))
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
            String query
    ) {
    }
}
