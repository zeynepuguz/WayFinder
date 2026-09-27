package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.NearbyPlaceResponse;
import com.nomi.wayfinder.dto.OpeningHoursDto;
import com.nomi.wayfinder.dto.PlaceCreateRequest;
import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceOpeningHours;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;

// Entity <-> DTO conversion in one place, so every endpoint returns places the same way
@Component
public class PlaceMapper {

    private final Clock clock;

    public PlaceMapper(Clock clock) {
        this.clock = clock;
    }

    public void apply(PlaceCreateRequest request, Place place) {
        place.setName(request.getName());
        place.setDescription(request.getDescription());
        place.setAddress(request.getAddress());
        place.setNeighborhood(request.getNeighborhood());
        place.setCoordinates(request.getLatitude(), request.getLongitude());
        place.setCategory(request.getCategory());
        place.setEstimatedCost(request.getEstimatedCost());
        place.setRating(request.getRating());
        place.setIndoor(request.isIndoor());
        place.setAvgVisitMinutes(request.getAvgVisitMinutes());
        place.setTags(Interests.normalize(request.getTags()));
        place.setSource(request.getSource() == null ? "ADMIN" : request.getSource());
        place.setSourceUrl(request.getSourceUrl());
        place.setLastVerifiedAt(request.getLastVerifiedAt());
        place.replaceOpeningHours(request.getOpeningHours() == null ? java.util.List.of() :
                request.getOpeningHours().stream()
                        .map(h -> new PlaceOpeningHours(h.dayOfWeek(), h.opensAt(), h.closesAt()))
                        .toList());
    }

    public PlaceResponse toResponse(Place place) {
        return fill(new PlaceResponse(), place);
    }

    public NearbyPlaceResponse toNearbyResponse(Place place, double distanceMeters) {
        NearbyPlaceResponse response = fill(new NearbyPlaceResponse(), place);
        response.setDistanceMeters((double) Math.round(distanceMeters));
        return response;
    }

    private <T extends PlaceResponse> T fill(T response, Place place) {
        ZonedDateTime now = ZonedDateTime.now(clock);

        response.setId(place.getId());
        response.setName(place.getName());
        response.setDescription(place.getDescription());
        response.setAddress(place.getAddress());
        response.setNeighborhood(place.getNeighborhood());
        response.setLatitude(place.getLatitude());
        response.setLongitude(place.getLongitude());
        response.setCategory(place.getCategory());
        response.setEstimatedCost(place.getEstimatedCost());
        response.setRating(place.getRating());
        response.setIndoor(place.isIndoor());
        response.setAvgVisitMinutes(place.getAvgVisitMinutes());
        response.setTags(place.getTags());
        response.setOpeningHours(place.getOpeningHours().stream()
                .map(h -> new OpeningHoursDto(h.getDayOfWeek(), h.getOpensAt(), h.getClosesAt()))
                .toList());
        response.setOpenNow(place.isOpenDuring(now.toLocalDate(), now.toLocalTime(), 0));
        response.setSource(place.getSource());
        response.setLastVerifiedAt(place.getLastVerifiedAt());

        return response;
    }
}
