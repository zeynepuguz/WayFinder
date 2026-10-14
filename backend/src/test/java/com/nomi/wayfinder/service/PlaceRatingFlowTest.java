package com.nomi.wayfinder.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Star ratings and saves end to end against the dev database: a café in a district without any rated place, rated
 * and saved by test accounts. Everything is undone afterwards (accounts deleted, sums recounted).
 */
@SpringBootTest(properties = "nomi.rate-limit.requests-per-minute=1000")
@AutoConfigureMockMvc
class PlaceRatingFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlaceCommunityService community;

    @Autowired
    private SavedPlaceService savedPlaces;

    @MockitoBean
    private MailService mail;

    private final JsonMapper json = JsonMapper.builder().build();

    private long cafeId;
    private String citySlug;
    private String districtSlug;

    @BeforeEach
    void pickACafe() {
        // A district where no place has a rating or a save yet: one 5-star vote must put the café first
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT p.id, c.slug AS city, d.slug AS district
                FROM places p JOIN districts d ON d.id = p.district_id JOIN cities c ON c.id = p.city_id
                WHERE p.category = 'CAFE' AND p.hidden = FALSE AND p.unconfirmed = FALSE AND p.review IS NULL
                  AND p.district_id IN (SELECT district_id FROM places WHERE district_id IS NOT NULL
                                        GROUP BY district_id
                                        HAVING max(rating) IS NULL AND max(user_rating_count) = 0
                                           AND max(favorite_count) = 0 AND count(*) BETWEEN 20 AND 2000)
                ORDER BY p.id LIMIT 1""");
        cafeId = ((Number) row.get("id")).longValue();
        citySlug = (String) row.get("city");
        districtSlug = (String) row.get("district");
    }

    @AfterEach
    void undo() {
        jdbc.update("DELETE FROM users WHERE email LIKE 'rating-test-%@example.com'");
        community.recountAll();
        assertThat(jdbc.queryForObject("""
                SELECT user_rating_count + favorite_count + cardinality(community_tags) FROM places WHERE id = ?""",
                Integer.class, cafeId)).isZero();
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // A signed-up, verified test account; returns {accessToken, user}
    private JsonNode account() throws Exception {
        String email = "rating-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName": "Deniz", "lastName": "Kaya", "email": "%s", "password": "dogru-sifre-123"}"""
                .formatted(email))).andExpect(status().isAccepted());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendSignUpCode(eq(email), anyString(), code.capture(), anyLong());
        return body(mvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "code": "%s"}""".formatted(email, code.getValue()))).andExpect(status().isCreated()));
    }

    private ResultActions as(JsonNode account, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + account.get("accessToken").asString()));
    }

    private ResultActions rate(JsonNode account, long placeId, int stars) throws Exception {
        return as(account, put("/api/v1/places/" + placeId + "/rating").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stars\": " + stars + "}"));
    }

    @Test
    void everyoneReadsTheRatingButOnlySignedInUsersRate() throws Exception {
        JsonNode rating = body(mvc.perform(get("/api/v1/places/" + cafeId + "/rating")).andExpect(status().isOk()));
        assertThat(rating.get("rateable").asBoolean()).isTrue();
        assertThat(rating.get("count").asInt()).isZero();
        assertThat(rating.get("average").isNull()).isTrue();

        mvc.perform(put("/api/v1/places/" + cafeId + "/rating").contentType(MediaType.APPLICATION_JSON)
                .content("{\"stars\": 5}")).andExpect(status().isUnauthorized());
    }

    @Test
    void oneRatingPerUserAndThePlaceCarriesTheSums() throws Exception {
        JsonNode first = account();
        JsonNode second = account();

        JsonNode afterFirst = body(rate(first, cafeId, 5).andExpect(status().isOk()));
        assertThat(afterFirst.get("mine").asInt()).isEqualTo(5);
        assertThat(afterFirst.get("count").asInt()).isEqualTo(1);

        // Rating again replaces the stars, it does not add a vote
        JsonNode changed = body(rate(first, cafeId, 3).andExpect(status().isOk()));
        assertThat(changed.get("count").asInt()).isEqualTo(1);
        assertThat(changed.get("average").asDouble()).isEqualTo(3.0);

        JsonNode afterSecond = body(rate(second, cafeId, 5).andExpect(status().isOk()));
        assertThat(afterSecond.get("count").asInt()).isEqualTo(2);
        assertThat(afterSecond.get("average").asDouble()).isEqualTo(4.0);
        assertThat(afterSecond.get("mine").asInt()).isEqualTo(5);

        JsonNode place = body(mvc.perform(get("/api/v1/places/" + cafeId)).andExpect(status().isOk()));
        assertThat(place.get("userRating").asDouble()).isEqualTo(4.0);
        assertThat(place.get("userRatingCount").asInt()).isEqualTo(2);

        JsonNode removed = body(as(second, delete("/api/v1/places/" + cafeId + "/rating")).andExpect(status().isOk()));
        assertThat(removed.get("count").asInt()).isEqualTo(1);
        assertThat(removed.get("mine").isNull()).isTrue();
    }

    @Test
    void starsAreOneToFiveAndOnlyForFoodAndDrink() throws Exception {
        JsonNode user = account();
        rate(user, cafeId, 6).andExpect(status().isBadRequest());
        rate(user, cafeId, 0).andExpect(status().isBadRequest());

        Long sight = jdbc.queryForObject(
                "SELECT id FROM places WHERE category = 'MUSEUM' AND hidden = FALSE ORDER BY id LIMIT 1", Long.class);
        rate(user, sight, 5).andExpect(status().isBadRequest());
        assertThat(body(mvc.perform(get("/api/v1/places/" + sight + "/rating"))).get("rateable").asBoolean()).isFalse();
    }

    @Test
    void theAreasFavouriteComesFirstInItsDistrict() throws Exception {
        rate(account(), cafeId, 5).andExpect(status().isOk());

        JsonNode page = body(mvc.perform(get("/api/v1/places").param("city", citySlug).param("district", districtSlug)
                .param("size", "5")).andExpect(status().isOk()));

        assertThat(page.get("content").get(0).get("id").asLong()).isEqualTo(cafeId);
    }

    private ResultActions answer(JsonNode account, long placeId, String... reasons) throws Exception {
        String list = String.join(",", java.util.Arrays.stream(reasons).map(r -> "\"" + r + "\"").toList());
        return as(account, put("/api/v1/places/" + placeId + "/rating/reasons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasons\": [" + list + "]}"));
    }

    @Test
    void whyAnswersComeAfterRatingAndFitTheStars() throws Exception {
        JsonNode user = account();
        answer(user, cafeId, "TASTY").andExpect(status().isBadRequest());

        rate(user, cafeId, 5).andExpect(status().isOk());
        answer(user, cafeId, "EXPENSIVE").andExpect(status().isBadRequest());
        answer(user, cafeId, "TASTY", "AFFORDABLE", "VIEW", "CLEAN", "QUIET").andExpect(status().isBadRequest());
        JsonNode answered = body(answer(user, cafeId, "TASTY", "AFFORDABLE").andExpect(status().isOk()));
        assertThat(answered.get("myReasons").toString()).isEqualTo("[\"TASTY\",\"AFFORDABLE\"]");

        // One star now: the liked answers no longer fit and go
        JsonNode changed = body(rate(user, cafeId, 1).andExpect(status().isOk()));
        assertThat(changed.get("myReasons").size()).isZero();
    }

    @Test
    void whatEnoughUsersSayBecomesAPlaceTagForThePlanner() throws Exception {
        for (int i = 0; i < 3; i++) {
            JsonNode user = account();
            rate(user, cafeId, 5).andExpect(status().isOk());
            answer(user, cafeId, "AFFORDABLE", i == 0 ? "TASTY" : "CLEAN").andExpect(status().isOk());
        }

        JsonNode rating = body(mvc.perform(get("/api/v1/places/" + cafeId + "/rating")).andExpect(status().isOk()));
        JsonNode top = rating.get("topReasons");
        assertThat(top.get(0).get("reason").asString()).isEqualTo("AFFORDABLE");
        assertThat(top.get(0).get("count").asInt()).isEqualTo(3);
        // A single user's answer is not shown as what people think
        assertThat(top.toString()).doesNotContain("TASTY");
        assertThat(jdbc.queryForObject("SELECT array_to_string(community_tags, ',') FROM places WHERE id = ?",
                String.class, cafeId)).isEqualTo("budget");
    }

    @Test
    void favouritePlacesForTheDistrictTheCityAndAllTurkey() throws Exception {
        long userId = account().get("user").get("id").asLong();
        savedPlaces.save(userId, cafeId);

        for (String[] scope : new String[][]{{citySlug, districtSlug}, {citySlug, null}, {null, null}}) {
            var request = get("/api/v1/places/favorites").param("limit", "30");
            if (scope[0] != null) {
                request = request.param("city", scope[0]);
            }
            if (scope[1] != null) {
                request = request.param("district", scope[1]);
            }
            JsonNode list = body(mvc.perform(request).andExpect(status().isOk()));
            assertThat(list.findValuesAsString("id")).as("scope %s/%s", scope[0], scope[1]).contains(String.valueOf(cafeId));
        }
        // Another city never shows it
        String other = citySlug.equals("ankara") ? "izmir" : "ankara";
        JsonNode elsewhere = body(mvc.perform(get("/api/v1/places/favorites").param("city", other).param("limit", "30")));
        assertThat(elsewhere.findValuesAsString("id")).doesNotContain(String.valueOf(cafeId));
    }

    @Test
    void savingAPlaceCountsOnThePlace() throws Exception {
        long userId = account().get("user").get("id").asLong();

        savedPlaces.save(userId, cafeId);
        assertThat(body(mvc.perform(get("/api/v1/places/" + cafeId))).get("favoriteCount").asInt()).isEqualTo(1);
        assertThat(community.savedPlaceIds(userId)).containsExactly(cafeId);

        savedPlaces.remove(userId, cafeId);
        assertThat(body(mvc.perform(get("/api/v1/places/" + cafeId))).get("favoriteCount").asInt()).isZero();
    }
}
