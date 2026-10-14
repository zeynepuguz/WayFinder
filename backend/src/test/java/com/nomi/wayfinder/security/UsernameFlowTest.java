package com.nomi.wayfinder.security;

import com.nomi.wayfinder.service.MailService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usernames end to end: chosen at sign-up (unique, by the rules), made from the first name when the app sends none,
 * changed in Profile. Test accounts are deleted afterwards.
 */
@SpringBootTest(properties = "nomi.rate-limit.requests-per-minute=1000")
@AutoConfigureMockMvc
class UsernameFlowTest {

    private static final String PASSWORD = "dogru-sifre-123";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MailService mail;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void deleteTestAccounts() {
        jdbc.update("DELETE FROM users WHERE email LIKE 'username-test-%@example.com'");
    }

    private static String newEmail() {
        return "username-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    // A username nobody has yet ("ut_3f9a1c")
    private static String newUsername() {
        return "ut_" + UUID.randomUUID().toString().substring(0, 6);
    }

    private ResultActions register(String email, String username) throws Exception {
        String field = username == null ? "" : ", \"username\": \"%s\"".formatted(username);
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName": "Şükrü", "lastName": "Yılmaz", "email": "%s", "password": "%s"%s}"""
                .formatted(email, PASSWORD, field)));
    }

    // Signed up and verified: the sign-up answer (tokens + user)
    private JsonNode signedUp(String email, String username) throws Exception {
        register(email, username).andExpect(status().isAccepted());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendSignUpCode(eq(email), anyString(), code.capture(), anyLong());
        String body = mvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"code\": \"%s\"}".formatted(email, code.getValue())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private ResultActions changeUsername(String token, String username) throws Exception {
        return mvc.perform(put("/api/v1/users/me/username").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\": \"%s\"}".formatted(username)));
    }

    @Test
    void theChosenUsernameIsKeptInLowercase() throws Exception {
        String username = newUsername();

        JsonNode user = signedUp(newEmail(), username.toUpperCase()).get("user");

        assertThat(user.get("username").asString()).isEqualTo(username);
    }

    @Test
    void aTakenOrInvalidUsernameIsRefusedAtSignUp() throws Exception {
        String username = newUsername();
        signedUp(newEmail(), username);

        register(newEmail(), username).andExpect(status().isConflict());
        register(newEmail(), "ab").andExpect(status().isBadRequest());
        register(newEmail(), "1zeynep").andExpect(status().isBadRequest());
        register(newEmail(), "zey nep").andExpect(status().isBadRequest());
        register(newEmail(), "admin").andExpect(status().isBadRequest());
        // Nothing was created for the refused sign-ups
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email LIKE 'username-test-%@example.com'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void withoutAChoiceTheUsernameComesFromTheFirstName() throws Exception {
        JsonNode user = signedUp(newEmail(), null).get("user");

        // "Şükrü" in plain letters, plus digits when "sukru" is taken
        assertThat(user.get("username").asString()).matches("sukru\\d*");
    }

    @Test
    void theUsernameCanBeChangedToAFreeOne() throws Exception {
        String token = signedUp(newEmail(), newUsername()).get("accessToken").asString();
        String other = newUsername();
        signedUp(newEmail(), other);
        String wanted = newUsername();

        JsonNode changed = json.readTree(changeUsername(token, wanted).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(changed.get("username").asString()).isEqualTo(wanted);
        changeUsername(token, wanted).andExpect(status().isOk());
        changeUsername(token, other).andExpect(status().isConflict());
        changeUsername(token, "x").andExpect(status().isBadRequest());
    }
}
