package com.nomi.wayfinder.google;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.google.GooglePlacesClient.GooglePlace;
import com.nomi.wayfinder.google.PlaceAvailabilityService.Availability;
import com.nomi.wayfinder.google.PlaceAvailabilityService.Status;
import com.nomi.wayfinder.osm.PlacesChangedEvent;
import com.nomi.wayfinder.repository.PlaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// "Seyir Cafe" in Amasya: open in OSM / Overture, closed for good on Google
class PlaceAvailabilityTest {

    private final GooglePlacesClient client = mock(GooglePlacesClient.class);
    private final PlaceRepository places = mock(PlaceRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final GooglePlacesBudget budget = mock(GooglePlacesBudget.class);
    private PlaceAvailabilityService service;

    @BeforeEach
    void setUp() {
        when(client.enabled()).thenReturn(true);
        when(budget.tryAcquire()).thenReturn(true);
        when(jdbc.update(anyString(), anyLong())).thenReturn(1);
        Place place = new Place();
        ReflectionTestUtils.setField(place, "id", 7L);
        place.setName("Amasya Seyir Cafe");
        place.setCategory(PlaceCategory.CAFE);
        place.setCoordinates(40.6472, 35.8206);
        ReflectionTestUtils.setField(place, "cityName", "Amasya");
        when(places.findById(7L)).thenReturn(Optional.of(place));
        service = new PlaceAvailabilityService(client, new GooglePlacesProperties("key", 300, Duration.ofSeconds(5)),
                places, jdbc, events, budget);
    }

    @Test
    void closedForGoodOnGoogleIsHiddenFromNomi() {
        when(client.search(anyString(), anyDouble(), anyDouble())).thenReturn(List.of(
                new GooglePlace("ChIJseyir", "Seyir Cafe", "CLOSED_PERMANENTLY", 40.6471, 35.8207)));

        Availability result = service.check(7L);

        assertThat(result.status()).isEqualTo(Status.CLOSED_PERMANENTLY);
        verify(jdbc).update(contains("hidden = TRUE"), eq(7L));
        verify(events).publishEvent(any(PlacesChangedEvent.class));
    }

    @Test
    void anOpenPlaceOpensByItsGooglePlaceIdNotANamesake() {
        when(client.search(anyString(), anyDouble(), anyDouble())).thenReturn(List.of(
                // Same name in another part of the city, 2 km away
                new GooglePlace("ChIJother", "Seyir Cafe", "OPERATIONAL", 40.6600, 35.8400),
                new GooglePlace("ChIJours", "Seyir Cafe & Bistro", "OPERATIONAL", 40.6473, 35.8205)));

        Availability result = service.check(7L);

        assertThat(result.status()).isEqualTo(Status.OPEN);
        assertThat(result.mapsUrl()).contains("query_place_id=ChIJours");
        verify(jdbc, never()).update(anyString(), anyLong());
    }

    @Test
    void noPlaceOfThisNameHereMayBeClosed() {
        when(client.search(anyString(), anyDouble(), anyDouble())).thenReturn(List.of(
                new GooglePlace("ChIJphone", "Es İletişim", "OPERATIONAL", 40.6472, 35.8206)));

        Availability result = service.check(7L);

        assertThat(result.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(result.mapsUrl()).contains("/maps/search/Amasya+Seyir+Cafe/@40.647200,35.820600,17z");
    }

    @Test
    void withoutAKeyNothingIsAskedAndTheSearchStaysAroundThePin() {
        when(client.enabled()).thenReturn(false);

        Availability result = service.check(7L);

        assertThat(result.status()).isEqualTo(Status.UNCHECKED);
        assertThat(result.mapsUrl()).contains("@40.647200,35.820600,17z");
        verify(client, never()).search(anyString(), anyDouble(), anyDouble());
    }

    @Test
    void whenTheFreeShareIsUsedUpGoogleIsNotAsked() {
        when(budget.tryAcquire()).thenReturn(false);

        assertThat(service.check(7L).status()).isEqualTo(Status.UNCHECKED);
        verify(client, never()).search(anyString(), anyDouble(), anyDouble());
    }

    @Test
    void namesMatchWithoutCaseLettersOrGenericWords() {
        assertThat(PlaceAvailabilityService.sameName("Akdağ Çayevi", "AKDAG CAYEVI")).isTrue();
        assertThat(PlaceAvailabilityService.sameName("Kardeşler Çay Evi", "Çayırova Kızılay Çay Bahçesi")).isFalse();
        assertThat(PlaceAvailabilityService.sameName("Mola Cafe", "Pusula Cafe")).isFalse();
    }
}
