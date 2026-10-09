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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sign-up and sign-in end to end against the dev database: nobody gets in without the code e-mailed to the address,
 * sessions are renewed with a refresh token that changes every time. Test accounts are deleted afterwards.
 */
@SpringBootTest(properties = {
        "nomi.security.owner-emails=auth-test-owner@example.com",
        "nomi.rate-limit.requests-per-minute=1000"
})
@AutoConfigureMockMvc
class AuthFlowTest {

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
        jdbc.update("DELETE FROM users WHERE email LIKE 'auth-test-%@example.com'");
    }

    private static String newEmail() {
        return "auth-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post("/api/v1/auth" + path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private ResultActions register(String email) throws Exception {
        return postJson("/register", """
                {"firstName": "Ayşe", "lastName": "Yılmaz", "email": "%s", "password": "%s"}""".formatted(email, PASSWORD));
    }

    private String lastSignUpCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendSignUpCode(eq(email), anyString(), code.capture(), anyLong());
        return code.getValue();
    }

    private String lastSignInCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendSignInCode(eq(email), code.capture(), anyLong());
        return code.getValue();
    }

    private ResultActions verifyCode(String path, String email, String code) throws Exception {
        return postJson(path, """
                {"email": "%s", "code": "%s"}""".formatted(email, code));
    }

    private String wrong(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    // Signed up and verified; returns the sign-up answer (tokens + user)
    private JsonNode signedUp(String email) throws Exception {
        register(email).andExpect(status().isAccepted());
        return body(verifyCode("/register/verify", email, lastSignUpCode(email)).andExpect(status().isCreated()));
    }

    @Test
    void signUpNeedsTheCodeEmailedToTheAddress() throws Exception {
        String email = newEmail();

        JsonNode sent = body(register(email).andExpect(status().isAccepted()));
        assertThat(sent.get("email").asString()).isEqualTo("au****@example.com");
        assertThat(sent.has("accessToken")).isFalse();
        String code = lastSignUpCode(email);
        assertThat(code).matches("\\d{6}");

        verifyCode("/register/verify", email, wrong(code)).andExpect(status().isBadRequest());
        JsonNode signedIn = body(verifyCode("/register/verify", email, code).andExpect(status().isCreated()));

        assertThat(signedIn.get("accessToken").asString()).isNotBlank();
        assertThat(signedIn.get("refreshToken").asString()).isNotBlank();
        assertThat(signedIn.get("user").get("firstName").asString()).isEqualTo("Ayşe");
        assertThat(signedIn.get("user").get("lastName").asString()).isEqualTo("Yılmaz");
        assertThat(signedIn.get("user").get("displayName").asString()).isEqualTo("Ayşe Yılmaz");
        // A code works once
        verifyCode("/register/verify", email, code).andExpect(status().isBadRequest());
    }

    @Test
    void signUpNeedsNameAndSurname() throws Exception {
        postJson("/register", """
                {"firstName": "Ayşe", "lastName": " ", "email": "%s", "password": "%s"}""".formatted(newEmail(), PASSWORD))
                .andExpect(status().isBadRequest());
        postJson("/register", """
                {"firstName": "Ayşe1", "lastName": "Yılmaz", "email": "%s", "password": "%s"}""".formatted(newEmail(), PASSWORD))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aVerifiedEmailCannotBeSignedUpAgain() throws Exception {
        String email = newEmail();
        signedUp(email);

        register(email).andExpect(status().isConflict());
    }

    @Test
    void signInNeedsThePasswordAndThenTheEmailedCode() throws Exception {
        String email = newEmail();
        signedUp(email);

        postJson("/login", """
                {"email": "%s", "password": "yanlis-sifre"}""".formatted(email)).andExpect(status().isUnauthorized());
        verify(mail, never()).sendSignInCode(anyString(), anyString(), anyLong());

        JsonNode sent = body(postJson("/login", """
                {"email": "%s", "password": "%s"}""".formatted(email, PASSWORD)).andExpect(status().isAccepted()));
        assertThat(sent.has("accessToken")).isFalse();
        String code = lastSignInCode(email);

        verifyCode("/login/verify", email, wrong(code)).andExpect(status().isBadRequest());
        JsonNode signedIn = body(verifyCode("/login/verify", email, code).andExpect(status().isOk()));

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + signedIn.get("accessToken").asString()))
                .andExpect(status().isOk());
    }

    @Test
    void fiveWrongPasswordsLockTheAccountForAWhile() throws Exception {
        String email = newEmail();
        signedUp(email);

        for (int i = 0; i < 5; i++) {
            postJson("/login", """
                    {"email": "%s", "password": "yanlis-%d"}""".formatted(email, i)).andExpect(status().isUnauthorized());
        }

        // Even the right password now
        postJson("/login", """
                {"email": "%s", "password": "%s"}""".formatted(email, PASSWORD)).andExpect(status().isTooManyRequests());
    }

    @Test
    void refreshGivesANewTokenAndTheOldOneStopsWorking() throws Exception {
        String first = signedUp(newEmail()).get("refreshToken").asString();

        JsonNode renewed = body(postJson("/refresh", """
                {"refreshToken": "%s"}""".formatted(first)).andExpect(status().isOk()));
        String second = renewed.get("refreshToken").asString();
        assertThat(second).isNotEqualTo(first);
        assertThat(renewed.get("accessToken").asString()).isNotBlank();

        postJson("/refresh", """
                {"refreshToken": "%s"}""".formatted(first)).andExpect(status().isUnauthorized());
        String third = body(postJson("/refresh", """
                {"refreshToken": "%s"}""".formatted(second)).andExpect(status().isOk())).get("refreshToken").asString();

        postJson("/logout", """
                {"refreshToken": "%s"}""".formatted(third)).andExpect(status().isNoContent());
        postJson("/refresh", """
                {"refreshToken": "%s"}""".formatted(third)).andExpect(status().isUnauthorized());
    }

    private static final String WINDOWS_CHROME = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
    private static final String ANDROID_APP = "Mozilla/5.0 (Linux; Android 14; Pixel 7; wv) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Version/4.0 Chrome/141.0.0.0 Mobile Safari/537.36";

    // Sign-in from a device (User-Agent); returns the answer with tokens
    private JsonNode signInFrom(String email, String userAgent) throws Exception {
        postJson("/login", """
                {"email": "%s", "password": "%s"}""".formatted(email, PASSWORD)).andExpect(status().isAccepted());
        return body(mvc.perform(post("/api/v1/auth/login/verify").contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", userAgent)
                        .content("""
                                {"email": "%s", "code": "%s"}""".formatted(email, lastSignInCode(email))))
                .andExpect(status().isOk()));
    }

    private JsonNode signedUpFrom(String email, String userAgent) throws Exception {
        register(email).andExpect(status().isAccepted());
        return body(mvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", userAgent)
                        .content("""
                                {"email": "%s", "code": "%s"}""".formatted(email, lastSignUpCode(email))))
                .andExpect(status().isCreated()));
    }

    private ResultActions authorized(MockHttpServletRequestBuilder request,
                                     JsonNode signedIn) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + signedIn.get("accessToken").asString()));
    }

    @Test
    void aSignInFromANewDeviceIsReportedByEmail() throws Exception {
        String email = newEmail();
        signedUpFrom(email, WINDOWS_CHROME);
        signInFrom(email, WINDOWS_CHROME);
        // The sign-up device and the same browser again: no alert
        verify(mail, never()).sendNewDeviceAlert(anyString(), any(), any(), anyString());

        signInFrom(email, ANDROID_APP);

        verify(mail).sendNewDeviceAlert(eq(email), eq("Nomi · Android"), anyString(), anyString());
    }

    @Test
    void theProfileListsOpenSessionsAndEndsOneAtOnce() throws Exception {
        String email = newEmail();
        JsonNode laptop = signedUpFrom(email, WINDOWS_CHROME);
        JsonNode phone = signInFrom(email, ANDROID_APP);

        JsonNode list = body(authorized(get("/api/v1/users/me/sessions"), laptop).andExpect(status().isOk()));
        assertThat(list.size()).isEqualTo(2);
        JsonNode first = list.get(0);
        JsonNode second = list.get(1);
        JsonNode phoneRow = first.get("device").asString().equals("Nomi · Android") ? first : second;
        JsonNode laptopRow = phoneRow == first ? second : first;
        assertThat(laptopRow.get("current").asBoolean()).isTrue();
        assertThat(phoneRow.get("current").asBoolean()).isFalse();

        authorized(delete("/api/v1/users/me/sessions/" + phoneRow.get("id").asLong()), laptop)
                .andExpect(status().isNoContent());

        // The phone's access token stops working right away, not in 15 minutes, and cannot be renewed
        authorized(get("/api/v1/users/me"), phone).andExpect(status().isUnauthorized());
        postJson("/refresh", """
                {"refreshToken": "%s"}""".formatted(phone.get("refreshToken").asString())).andExpect(status().isUnauthorized());
        authorized(get("/api/v1/users/me"), laptop).andExpect(status().isOk());
    }

    @Test
    void endOthersKeepsOnlyThisDevice() throws Exception {
        String email = newEmail();
        JsonNode laptop = signedUpFrom(email, WINDOWS_CHROME);
        JsonNode phone = signInFrom(email, ANDROID_APP);

        JsonNode ended = body(authorized(post("/api/v1/users/me/sessions/end-others"), laptop)
                .andExpect(status().isOk()));

        assertThat(ended.get("ended").asInt()).isEqualTo(1);
        authorized(get("/api/v1/users/me"), phone).andExpect(status().isUnauthorized());
        assertThat(body(authorized(get("/api/v1/users/me/sessions"), laptop)).size()).isEqualTo(1);
    }

    @Test
    void nobodyCanEndAnotherUsersSession() throws Exception {
        JsonNode mine = signedUpFrom(newEmail(), WINDOWS_CHROME);
        JsonNode theirs = signedUpFrom(newEmail(), ANDROID_APP);
        long theirSession = body(authorized(get("/api/v1/users/me/sessions"), theirs)).get(0).get("id").asLong();

        authorized(delete("/api/v1/users/me/sessions/" + theirSession), mine)
                .andExpect(status().isNotFound());
        authorized(get("/api/v1/users/me"), theirs).andExpect(status().isOk());
    }

    @Test
    void aStolenOldTokenUsedLaterEndsTheSession() throws Exception {
        String first = signedUp(newEmail()).get("refreshToken").asString();
        String second = body(postJson("/refresh", """
                {"refreshToken": "%s"}""".formatted(first)).andExpect(status().isOk())).get("refreshToken").asString();
        // The replacement was more than the grace minute ago
        jdbc.update("UPDATE user_sessions SET replaced_at = replaced_at - INTERVAL '5 minutes' WHERE previous_hash = ?",
                SessionService.hash(first));

        postJson("/refresh", """
                {"refreshToken": "%s"}""".formatted(first)).andExpect(status().isUnauthorized());

        // The real owner's token is ended too: they sign in again (with the code), the thief cannot
        postJson("/refresh", """
                {"refreshToken": "%s"}""".formatted(second)).andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionUnusedForAWeekIsOver() throws Exception {
        String token = signedUp(newEmail()).get("refreshToken").asString();
        jdbc.update("UPDATE user_sessions SET expires_at = now() - INTERVAL '1 second' WHERE token_hash = ?",
                SessionService.hash(token));

        postJson("/refresh", """
                {"refreshToken": "%s"}""".formatted(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void theOwnerEmailBecomesAdminOnlyOnceProven() throws Exception {
        String owner = "auth-test-owner@example.com";
        register(owner).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, owner)).isEqualTo("USER");

        // Someone who knows the password but not the mailbox gets nowhere: the code goes to the owner
        postJson("/login", """
                {"email": "%s", "password": "%s"}""".formatted(owner, PASSWORD)).andExpect(status().isAccepted());
        verify(mail, never()).sendSignInCode(anyString(), anyString(), anyLong());

        JsonNode signedIn = body(verifyCode("/register/verify", owner, lastSignUpCode(owner)).andExpect(status().isCreated()));
        assertThat(signedIn.get("user").get("role").asString()).isEqualTo("ADMIN");
    }

    @Test
    void resendOnlyWorksWhileASignInIsRunning() throws Exception {
        String email = newEmail();
        signedUp(email);

        // No sign-in started: nothing is sent
        postJson("/code/resend", """
                {"email": "%s", "purpose": "SIGN_IN"}""".formatted(email)).andExpect(status().isAccepted());
        verify(mail, never()).sendSignInCode(anyString(), anyString(), anyLong());
    }
}
