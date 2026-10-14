package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.entity.Route;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Liked routes end to end against the dev database: a day in Kadıköy that one user liked is given to another user
 * asking for the same kind of day nearby, and listed as the district's community route. Accounts (and with them their
 * routes and feedback) are deleted afterwards.
 */
@SpringBootTest(properties = "nomi.rate-limit.requests-per-minute=1000")
@AutoConfigureMockMvc
class CommunityRouteFlowTest {

    // Kadıköy, near the pier: plenty of places for breakfast, lunch and coffee
    private static final double LAT = 40.9905;
    private static final double LON = 29.0237;
    private static final List<StopType> STOPS = List.of(StopType.BREAKFAST, StopType.LUNCH, StopType.COFFEE);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RouteService routes;

    @Autowired
    private TransactionTemplate tx;

    @MockitoBean
    private MailService mail;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void deleteTestAccounts() {
        jdbc.update("DELETE FROM users WHERE email LIKE 'route-test-%@example.com'");
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private JsonNode account() throws Exception {
        String email = "route-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName": "Ece", "lastName": "Demir", "email": "%s", "password": "dogru-sifre-123"}"""
                .formatted(email))).andExpect(status().isAccepted());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendSignUpCode(eq(email), anyString(), code.capture(), anyLong());
        return body(mvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "code": "%s"}""".formatted(email, code.getValue()))).andExpect(status().isCreated()));
    }

    private ResultActions as(JsonNode account, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + account.get("accessToken").asString()));
    }

    // The same kind of day, a few streets apart (the service directly: creating routes needs a pass over HTTP)
    private Route plan(JsonNode account, double latitude) {
        long userId = account.get("user").get("id").asLong();
        RoutePlanRequest request = new RoutePlanRequest(latitude, LON, LocalDate.now().plusDays(1), LocalTime.of(9, 30),
                LocalTime.of(16, 0), 2, null, WalkingTolerance.MEDIUM, STOPS, List.of(), null, null, null, null, null);
        return tx.execute(status -> {
            Route route = routes.createRoute(userId, request);
            route.getStops().forEach(s -> s.getPlace().getName());
            return route;
        });
    }

    private ResultActions like(JsonNode account, long routeId, boolean liked) throws Exception {
        return as(account, put("/api/v1/routes/" + routeId + "/feedback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"liked\": " + liked + "}"));
    }

    private List<Long> placeIds(long routeId) {
        return jdbc.queryForList("SELECT place_id FROM route_stops WHERE route_id = ? ORDER BY position", Long.class,
                routeId);
    }

    @Test
    void onlyTheOwnerRatesTheirRoute() throws Exception {
        JsonNode owner = account();
        JsonNode stranger = account();
        Route route = plan(owner, LAT);

        like(stranger, route.getId(), true).andExpect(status().isNotFound());
        assertThat(body(like(owner, route.getId(), true).andExpect(status().isOk())).get("liked").asBoolean()).isTrue();
        assertThat(body(as(owner, get("/api/v1/routes/" + route.getId() + "/feedback"))).get("liked").asBoolean())
                .isTrue();
    }

    @Test
    void aLikedDayIsGivenToTheNextUserAskingForTheSameNearby() throws Exception {
        JsonNode first = account();
        Route liked = plan(first, LAT);
        assertThat(liked.getStops()).hasSizeGreaterThanOrEqualTo(2);
        like(first, liked.getId(), true).andExpect(status().isOk());

        // ~300 m away, same stops, same time of day
        Route next = plan(account(), LAT + 0.003);

        assertThat(next.getBasedOnRouteId()).isEqualTo(liked.getId());
        assertThat(placeIds(next.getId())).containsExactlyElementsOf(placeIds(liked.getId()));
        assertThat(next.getNotes().getFirst()).contains("beğenen Nomi kullanıcılarının rotasına göre");
    }

    @Test
    void aDislikedDayIsNotGivenAgain() throws Exception {
        JsonNode first = account();
        Route route = plan(first, LAT);
        like(first, route.getId(), false).andExpect(status().isOk());

        assertThat(plan(account(), LAT + 0.003).getBasedOnRouteId()).isNull();
    }

    private JsonNode find(JsonNode list, Route route) {
        for (JsonNode r : list) {
            if (r.get("key").asString().equals("c-" + RouteTemplates.sequenceKey(route))) {
                return r;
            }
        }
        return null;
    }

    @Test
    void savingARouteMakesItAFavouriteForTheCityAndAllTurkey() throws Exception {
        JsonNode first = account();
        Route route = plan(first, LAT);
        // The heart on the route page ("Kaydet"), no 👍 (set directly: saving over HTTP needs a pass)
        jdbc.update("UPDATE routes SET saved = TRUE WHERE id = ?", route.getId());

        JsonNode city = body(mvc.perform(get("/api/v1/routes/community").param("city", "istanbul")).andExpect(status().isOk()));
        JsonNode turkey = body(mvc.perform(get("/api/v1/routes/community")).andExpect(status().isOk()));
        JsonNode otherCity = body(mvc.perform(get("/api/v1/routes/community").param("city", "ankara")).andExpect(status().isOk()));

        assertThat(find(city, route)).isNotNull();
        assertThat(find(turkey, route)).isNotNull();
        assertThat(find(otherCity, route)).isNull();

        // ... and the next user asking for the same day nearby gets it
        assertThat(plan(account(), LAT + 0.003).getBasedOnRouteId()).isEqualTo(route.getId());

        // 👎 wins over the save
        like(first, route.getId(), false).andExpect(status().isOk());
        assertThat(find(body(mvc.perform(get("/api/v1/routes/community"))), route)).isNull();
    }

    @Test
    void theDistrictListsItsLikedRoutes() throws Exception {
        JsonNode first = account();
        Route route = plan(first, LAT);
        like(first, route.getId(), true).andExpect(status().isOk());

        JsonNode list = body(mvc.perform(get("/api/v1/routes/community").param("city", "istanbul")
                .param("district", "kadikoy")).andExpect(status().isOk()));

        JsonNode ours = null;
        for (JsonNode r : list) {
            if (r.get("key").asString().equals("c-" + RouteTemplates.sequenceKey(route))) {
                ours = r;
            }
        }
        assertThat(ours).isNotNull();
        assertThat(ours.get("popularityNote").asString()).isEqualTo("1 kişi favoriledi");
        assertThat(ours.get("stops").size()).isEqualTo(route.getStops().size());
    }
}
