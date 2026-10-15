package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.RouteDtos.RoutePlanRequest;
import com.nomi.wayfinder.dto.RouteDtos.RouteSummary;
import com.nomi.wayfinder.dto.RouteDtos.RouteUpdateRequest;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A route the assistant plans stays in its chat: not in "Rotalarım" nor Ana Sayfa's "Yarınki rotan" until the user
 * adds it (or saves it). Against the dev database; the test account and its routes are deleted afterwards.
 */
@SpringBootTest(properties = "nomi.rate-limit.requests-per-minute=1000")
@AutoConfigureMockMvc
class RouteListedFlowTest {

    // Kadıköy, near the pier
    private static final double LAT = 40.9905;
    private static final double LON = 29.0237;

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
        jdbc.update("DELETE FROM users WHERE email LIKE 'listed-test-%@example.com'");
    }

    private long userId() throws Exception {
        String email = "listed-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName": "Ece", "lastName": "Demir", "email": "%s", "password": "dogru-sifre-123"}"""
                .formatted(email))).andExpect(status().isAccepted());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendSignUpCode(eq(email), anyString(), code.capture(), anyLong());
        String body = mvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "code": "%s"}""".formatted(email, code.getValue())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("user").get("id").asLong();
    }

    // Tomorrow's day in Kadıköy (the service directly: creating routes needs a pass over HTTP)
    private Route plan(long userId) {
        RoutePlanRequest request = new RoutePlanRequest(LAT, LON, LocalDate.now().plusDays(1), LocalTime.of(9, 30),
                LocalTime.of(16, 0), 2, null, WalkingTolerance.MEDIUM,
                List.of(StopType.BREAKFAST, StopType.LUNCH, StopType.COFFEE), List.of(), null, null, null, null, null);
        return tx.execute(status -> routes.createRoute(userId, request));
    }

    private List<Long> listed(long userId) {
        return routes.listRoutes(userId, false).stream().map(RouteSummary::id).toList();
    }

    @Test
    void theAssistantsRouteIsInRotalarimOnlyOnceTheUserAddsIt() throws Exception {
        long userId = userId();
        Route route = plan(userId);
        assertThat(route.isListed()).isTrue();
        assertThat(listed(userId)).contains(route.getId());

        // What the assistant does with a route it planned
        assertThat(routes.keepInChatOnly(userId, route.getId()).listed()).isFalse();
        assertThat(listed(userId)).doesNotContain(route.getId());
        assertThat(routes.findTomorrowRouteSummary(userId)).isEmpty();
        // Still opened from the chat
        assertThat(routes.getRoute(userId, route.getId()).id()).isEqualTo(route.getId());

        // "Rotalarıma ekle"
        assertThat(routes.updateRoute(userId, route.getId(), new RouteUpdateRequest(null, null, null, true)).listed()).isTrue();
        assertThat(listed(userId)).contains(route.getId());
        assertThat(routes.findTomorrowRouteSummary(userId)).isPresent();
    }

    @Test
    void savingAnAssistantsRouteAlsoAddsIt() throws Exception {
        long userId = userId();
        Route route = plan(userId);
        routes.keepInChatOnly(userId, route.getId());

        routes.updateRoute(userId, route.getId(), new RouteUpdateRequest(null, true, null));

        assertThat(listed(userId)).contains(route.getId());
    }
}
