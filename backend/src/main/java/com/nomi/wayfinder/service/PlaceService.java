package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.PlaceCreateRequest;
import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.repository.PlaceRepository;
import org.springframework.stereotype.Service;
import com.nomi.wayfinder.exception.PlaceNotFoundException;

import java.util.List;

@Service
public class PlaceService {

    private final PlaceRepository placeRepository;

    public PlaceService(PlaceRepository placeRepository) {
        this.placeRepository = placeRepository;
    }

    public List<PlaceResponse> getAllPlaces() {
        return placeRepository.findAll()
                .stream()
                .map(place -> {
                    PlaceResponse response = new PlaceResponse();

                    response.setId(place.getId());
                    response.setName(place.getName());
                    response.setDescription(place.getDescription());
                    response.setAddress(place.getAddress());
                    response.setLatitude(place.getLatitude());
                    response.setLongitude(place.getLongitude());
                    response.setCategory(place.getCategory());
                    response.setEstimatedCost(place.getEstimatedCost());
                    response.setRating(place.getRating());

                    return response;
                })
                .toList();
    }

    public PlaceResponse createPlace(PlaceCreateRequest request) {

        // DTO -> Entity
        Place place = new Place();

        place.setName(request.getName());
        place.setDescription(request.getDescription());
        place.setAddress(request.getAddress());
        place.setLatitude(request.getLatitude());
        place.setLongitude(request.getLongitude());
        place.setCategory(request.getCategory());
        place.setEstimatedCost(request.getEstimatedCost());

        // Entity -> Database
        Place savedPlace = placeRepository.save(place);

        // Entity -> Response DTO
        PlaceResponse response = new PlaceResponse();

        response.setId(savedPlace.getId());
        response.setName(savedPlace.getName());
        response.setDescription(savedPlace.getDescription());
        response.setAddress(savedPlace.getAddress());
        response.setLatitude(savedPlace.getLatitude());
        response.setLongitude(savedPlace.getLongitude());
        response.setCategory(savedPlace.getCategory());
        response.setEstimatedCost(savedPlace.getEstimatedCost());
        response.setRating(savedPlace.getRating());

        return response;
    }

    public PlaceResponse getPlaceById(Long id) {

        Place place = placeRepository.findById(id)
                .orElseThrow(() -> new PlaceNotFoundException(id));

        return toResponse(place);
    }

    private PlaceResponse toResponse(Place place) {

        PlaceResponse response = new PlaceResponse();

        response.setId(place.getId());
        response.setName(place.getName());
        response.setDescription(place.getDescription());
        response.setAddress(place.getAddress());
        response.setLatitude(place.getLatitude());
        response.setLongitude(place.getLongitude());
        response.setCategory(place.getCategory());
        response.setEstimatedCost(place.getEstimatedCost());
        response.setRating(place.getRating());

        return response;
    }

    public PlaceResponse updatePlace(Long id, PlaceCreateRequest request) {

        Place place = placeRepository.findById(id)
                .orElseThrow(() -> new PlaceNotFoundException(id));

        place.setName(request.getName());
        place.setDescription(request.getDescription());
        place.setAddress(request.getAddress());
        place.setLatitude(request.getLatitude());
        place.setLongitude(request.getLongitude());
        place.setCategory(request.getCategory());
        place.setEstimatedCost(request.getEstimatedCost());

        Place updatedPlace = placeRepository.save(place);

        return toResponse(updatedPlace);
    }

    public void deletePlace(Long id) {

        Place place = placeRepository.findById(id)
                .orElseThrow(() -> new PlaceNotFoundException(id));

        placeRepository.delete(place);
    }


}