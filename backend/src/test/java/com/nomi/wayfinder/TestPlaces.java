package com.nomi.wayfinder;

import com.nomi.wayfinder.entity.Place;
import com.nomi.wayfinder.entity.PlaceCategory;
import com.nomi.wayfinder.entity.PlaceOpeningHours;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

// Small builder for Place objects in unit tests (no database)
public final class TestPlaces {

    private TestPlaces() {
    }

    public static Place place(long id, String name, PlaceCategory category, boolean indoor,
                              double rating, int cost, String... tags) {
        Place place = new Place();
        ReflectionTestUtils.setField(place, "id", id);
        place.setName(name);
        place.setCategory(category);
        place.setIndoor(indoor);
        place.setRating(rating);
        place.setEstimatedCost(cost);
        place.setCoordinates(40.99, 29.02);
        place.setTags(List.of(tags));
        return place;
    }

    public static Place openEveryDay(Place place, String opens, String closes) {
        List<PlaceOpeningHours> hours = new ArrayList<>();
        for (int day = 1; day <= 7; day++) {
            hours.add(new PlaceOpeningHours(day, LocalTime.parse(opens), LocalTime.parse(closes)));
        }
        place.replaceOpeningHours(hours);
        return place;
    }
}
