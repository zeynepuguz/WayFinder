package com.nomi.wayfinder.service;

import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.repository.PlaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// GET /api/v1/places/in-area: box validation and the reference point for distances
class PlacesInAreaTest {

    private final PlaceRepository repository = mock(PlaceRepository.class);
    private final PlaceService service = new PlaceService(repository, new PlaceMapper(Clock.systemUTC()));

    @Test
    void rejectsTooLargeOrInvertedBoxesWith400() {
        assertBadRequest(() -> service.getPlacesInArea(40.8, 28.8, 41.5, 29.0, null, null, null, 200));
        assertBadRequest(() -> service.getPlacesInArea(40.9, 28.5, 41.0, 29.2, null, null, null, 200));
        assertBadRequest(() -> service.getPlacesInArea(41.0, 29.0, 40.9, 29.1, null, null, null, 200));
        assertBadRequest(() -> service.getPlacesInArea(40.9, 29.0, 41.0, 29.1, 40.95, null, null, 200));
        verifyNoInteractions(repository);
    }

    @Test
    void usesTheUserLocationOrElseTheBoxCenter() {
        when(repository.findInArea(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), anyInt()))
                .thenReturn(List.of());
        when(repository.findByIdIn(anyCollection())).thenReturn(List.of());

        service.getPlacesInArea(40.9, 29.0, 41.1, 29.2, null, null, PlaceCategory.CAFE, 200);
        verify(repository).findInArea(eq(40.9), eq(29.0), eq(41.1), eq(29.2),
                doubleThat(v -> Math.abs(v - 41.0) < 1e-9), doubleThat(v -> Math.abs(v - 29.1) < 1e-9), eq("CAFE"), eq(200));

        service.getPlacesInArea(40.9, 29.0, 41.1, 29.2, 40.95, 29.05, null, 50);
        verify(repository).findInArea(40.9, 29.0, 41.1, 29.2, 40.95, 29.05, null, 50);
    }

    private static void assertBadRequest(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
