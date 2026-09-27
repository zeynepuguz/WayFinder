package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.PlaceImage;
import com.nomi.wayfinder.dto.PlaceResponse;
import com.nomi.wayfinder.dto.RouteDtos.StopPlace;
import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.entity.RouteStop;
import com.nomi.wayfinder.entity.StopType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static com.nomi.wayfinder.TestPlaces.place;
import static org.assertj.core.api.Assertions.assertThat;

// The Commons photo (with attribution) reaches place, nearby and route stop DTOs; no photo -> "image": null
class PlaceImageMappingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneId.of("Europe/Istanbul"));

    private final PlaceMapper placeMapper = new PlaceMapper(CLOCK);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void placeWithPhotoHasImageObject() {
        Place museum = withImage(place(1, "Ayasofya", PlaceCategory.MUSEUM, true, 4.8, 0));

        PlaceResponse response = placeMapper.toResponse(museum);
        assertThat(response.getImage()).isEqualTo(new PlaceImage(
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Hagia.jpg/800px-Hagia.jpg",
                "Arild Vågen", "CC BY-SA 3.0", "https://commons.wikimedia.org/wiki/File:Hagia.jpg"));
        assertThat(placeMapper.toNearbyResponse(museum, 120).getImage()).isEqualTo(response.getImage());

        JsonNode image = jsonMapper.readTree(jsonMapper.writeValueAsString(response)).get("image");
        assertThat(image.get("url").isString()).isTrue();
        assertThat(image.get("author").asString()).isEqualTo("Arild Vågen");
        assertThat(image.get("license").asString()).isEqualTo("CC BY-SA 3.0");
        assertThat(image.get("sourceUrl").asString()).isEqualTo("https://commons.wikimedia.org/wiki/File:Hagia.jpg");
    }

    @Test
    void placeWithoutPhotoHasNullImage() {
        Place cafe = place(2, "Kafe", PlaceCategory.CAFE, true, 4.2, 100);
        // Checked, nothing usable found: still no image
        ReflectionTestUtils.setField(cafe, "wikidata", "Q1");
        ReflectionTestUtils.setField(cafe, "imageCheckedAt", Instant.now());

        PlaceResponse response = placeMapper.toResponse(cafe);
        assertThat(response.getImage()).isNull();

        JsonNode json = jsonMapper.readTree(jsonMapper.writeValueAsString(response));
        assertThat(json.has("image")).isTrue();
        assertThat(json.get("image").isNull()).isTrue();
    }

    @Test
    void routeStopPlaceCarriesTheImage() {
        Place museum = withImage(place(1, "Ayasofya", PlaceCategory.MUSEUM, true, 4.8, 0));
        Place cafe = place(2, "Kafe", PlaceCategory.CAFE, true, 4.2, 100);

        Route route = new Route();
        route.setTitle("Gün");
        route.setDate(LocalDate.of(2026, 9, 28));
        route.setStartLocation(41.0, 28.98);
        route.setStartTime(LocalTime.of(10, 0));
        route.setEndTime(LocalTime.of(18, 0));
        route.setPartySize(1);
        route.replaceStops(List.of(stop(museum, 0), stop(cafe, 1)));

        List<StopPlace> places = new RouteMapper().toResponse(route).stops().stream()
                .map(s -> s.place()).toList();
        assertThat(places.get(0).image()).isEqualTo(PlaceImage.of(museum));
        assertThat(places.get(0).image().license()).isEqualTo("CC BY-SA 3.0");
        assertThat(places.get(1).image()).isNull();
    }

    private static RouteStop stop(Place place, int position) {
        RouteStop stop = new RouteStop();
        stop.setPlace(place);
        stop.setStopType(position == 0 ? StopType.SIGHTSEEING : StopType.COFFEE);
        stop.setPlannedStart(LocalTime.of(10 + position, 0));
        stop.setPlannedEnd(LocalTime.of(10 + position, 45));
        return stop;
    }

    private static Place withImage(Place place) {
        ReflectionTestUtils.setField(place, "wikidata", "Q12506");
        ReflectionTestUtils.setField(place, "imageUrl",
                "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Hagia.jpg/800px-Hagia.jpg");
        ReflectionTestUtils.setField(place, "imageAuthor", "Arild Vågen");
        ReflectionTestUtils.setField(place, "imageLicense", "CC BY-SA 3.0");
        ReflectionTestUtils.setField(place, "imageSourceUrl", "https://commons.wikimedia.org/wiki/File:Hagia.jpg");
        return place;
    }
}
