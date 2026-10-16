package com.nomi.wayfinder.geocoding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full addresses of places without a street address: OpenStreetMap's parts written the Turkish way, stored once.
 * The tests never call Nominatim (nomi.geocoding.enabled=false); the stored address is restored afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlaceAddressTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private Long placeId;
    private String keptAddress;
    private String keptGeoAddress;

    @AfterEach
    void restore() {
        if (placeId != null) {
            jdbc.update("UPDATE places SET address = ?, geo_address = ?, geo_address_checked_at = NULL WHERE id = ?",
                    keptAddress, keptGeoAddress, placeId);
        }
    }

    @Test
    void nominatimsPartsTheTurkishWay() {
        // What Nominatim answered for Burmalı Minare Camii, Amasya
        assertThat(PlaceAddressService.format(Map.of("road", "Taşhan Sokak", "suburb", "Dere", "city", "Amasya",
                "county", "Amasya", "province", "Amasya", "postcode", "05100")))
                .isEqualTo("Dere Mah., Taşhan Sokak, 05100 Merkez/Amasya");
        assertThat(PlaceAddressService.format(Map.of("road", "Moda Caddesi", "house_number", "12",
                "neighbourhood", "Caferağa Mahallesi", "county", "Kadıköy", "province", "İstanbul")))
                .isEqualTo("Caferağa Mahallesi, Moda Caddesi No:12, Kadıköy/İstanbul");
        // District and city alone are no full address: the page already shows them
        assertThat(PlaceAddressService.format(Map.of("county", "Of", "province", "Trabzon"))).isNull();
    }

    @Test
    void theStoredAddressIsServedAndListedWithThePlace() throws Exception {
        placeId = jdbc.queryForObject("SELECT id FROM places WHERE NOT hidden AND (address IS NULL OR address = '') LIMIT 1",
                Long.class);
        Map<String, Object> kept = jdbc.queryForMap("SELECT address, geo_address FROM places WHERE id = ?", placeId);
        keptAddress = (String) kept.get("address");
        keptGeoAddress = (String) kept.get("geo_address");
        jdbc.update("UPDATE places SET geo_address = ?, geo_address_checked_at = now() WHERE id = ?",
                "Dere Mah., Taşhan Sokak, 05100 Merkez/Amasya", placeId);

        mvc.perform(get("/api/v1/places/" + placeId + "/address")).andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("Dere Mah., Taşhan Sokak, 05100 Merkez/Amasya"));
        // The place itself carries it too (lists, routes)
        mvc.perform(get("/api/v1/places/" + placeId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("Dere Mah., Taşhan Sokak, 05100 Merkez/Amasya"));
    }

    @Test
    void withoutAStoredAddressAndLookupsOffTheAnswerIsEmpty() throws Exception {
        placeId = jdbc.queryForObject("""
                SELECT id FROM places WHERE NOT hidden AND (address IS NULL OR address = '') AND geo_address IS NULL LIMIT 1
                """, Long.class);
        keptAddress = null;
        keptGeoAddress = null;

        mvc.perform(get("/api/v1/places/" + placeId + "/address")).andExpect(status().isOk())
                .andExpect(jsonPath("$.address").doesNotExist());
        mvc.perform(get("/api/v1/places/999999999/address")).andExpect(status().isNotFound());
    }
}
