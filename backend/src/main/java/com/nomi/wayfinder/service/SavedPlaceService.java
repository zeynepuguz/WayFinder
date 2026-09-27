package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.entity.SavedPlace;
import com.nomi.wayfinder.exception.PlaceNotFoundException;
import com.nomi.wayfinder.repository.PlaceRepository;
import com.nomi.wayfinder.repository.SavedPlaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SavedPlaceService {

    private final SavedPlaceRepository savedPlaceRepository;
    private final PlaceRepository placeRepository;
    private final PlaceMapper placeMapper;

    public SavedPlaceService(
            SavedPlaceRepository savedPlaceRepository,
            PlaceRepository placeRepository,
            PlaceMapper placeMapper
    ) {
        this.savedPlaceRepository = savedPlaceRepository;
        this.placeRepository = placeRepository;
        this.placeMapper = placeMapper;
    }

    @Transactional(readOnly = true)
    public List<PlaceResponse> list(Long userId) {
        return savedPlaceRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(saved -> placeMapper.toResponse(saved.getPlace()))
                .toList();
    }

    @Transactional
    public void save(Long userId, Long placeId) {
        if (!placeRepository.existsById(placeId)) {
            throw new PlaceNotFoundException(placeId);
        }
        SavedPlace.Key key = new SavedPlace.Key(userId, placeId);
        if (!savedPlaceRepository.existsById(key)) {
            savedPlaceRepository.save(new SavedPlace(userId, placeId));
        }
    }

    @Transactional
    public void remove(Long userId, Long placeId) {
        savedPlaceRepository.deleteById(new SavedPlace.Key(userId, placeId));
    }
}
