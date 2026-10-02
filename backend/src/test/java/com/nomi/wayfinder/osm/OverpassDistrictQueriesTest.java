package com.nomi.wayfinder.osm;

import com.nomi.wayfinder.config.NomiProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// A busy Overpass server answers a district's food query in seconds, but the sights / markets queries cost ~90 s
// whatever the area: food district by district, the other two once for the city, each ending before we give up
class OverpassDistrictQueriesTest {

    @Test
    void foodPerDistrictSightsAndMarketsOncePerCity() {
        List<String> sent = new ArrayList<>();
        NomiProperties.Osm osm = new NomiProperties.Osm(false, "-", "all", Duration.ZERO, Duration.ofDays(20),
                List.of("http://localhost:1"), Duration.ofSeconds(1), Duration.ofMinutes(5), 1, Duration.ZERO);
        OverpassClient client = new OverpassClient(
                new NomiProperties(null, null, null, null, null, null, null, osm, null), JsonMapper.builder().build()) {
            @Override
            List<OverpassResponse.Element> fetch(String query, boolean requireElements) {
                sent.add(query);
                return List.of();
            }
        };

        client.fetchPlaces(167216, List.of(1249246L, 1249247L));

        // Two districts' food, then the city's sights / markets / worship one statement each
        assertThat(sent.get(0)).contains("area(id:3601249246)", "[timeout:120]", "\"amenity\"~\"^(cafe|restaurant");
        assertThat(sent.get(1)).contains("area(id:3601249247)", "[timeout:120]");
        List<String> city = sent.subList(2, sent.size());
        assertThat(city).hasSize(12).allMatch(q -> q.contains("area(id:3600167216)") && q.contains("[timeout:240]")
                && q.contains("out center meta;") && q.split("nwr").length == 2);
        assertThat(city).anyMatch(q -> q.contains("[\"historic\"]")).anyMatch(q -> q.contains("place_of_worship"))
                .anyMatch(q -> q.contains("supermarket"));
    }
}
