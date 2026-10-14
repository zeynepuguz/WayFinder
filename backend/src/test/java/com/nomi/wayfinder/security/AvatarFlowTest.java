package com.nomi.wayfinder.security;

import com.nomi.wayfinder.service.MailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile photos end to end: stored square and without metadata, shown on the public profile (never the real name
 * or e-mail), removed by the user or an admin. Test accounts are deleted afterwards.
 */
@SpringBootTest(properties = "nomi.rate-limit.requests-per-minute=1000")
@AutoConfigureMockMvc
class AvatarFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private MailService mail;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void deleteTestAccounts() {
        jdbc.update("DELETE FROM users WHERE email LIKE 'avatar-test-%@example.com'");
    }

    // Signed up and verified: the sign-up answer (tokens + user)
    private JsonNode signedUp(String username) throws Exception {
        String email = "avatar-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"firstName": "Ayşe", "lastName": "Yılmaz", "email": "%s", "password": "dogru-sifre-123", "username": "%s"}"""
                .formatted(email, username))).andExpect(status().isAccepted());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendSignUpCode(eq(email), anyString(), code.capture(), anyLong());
        return read(mvc.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"code\": \"%s\"}".formatted(email, code.getValue())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode read(String body) throws Exception {
        return json.readTree(body);
    }

    // A 300×200 PNG
    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void aProfilePhotoIsStoredSquareAndShownOnThePublicProfile() throws Exception {
        String username = "av_" + UUID.randomUUID().toString().substring(0, 6);
        String token = signedUp(username).get("accessToken").asString();

        JsonNode set = read(mvc.perform(multipart(HttpMethod.PUT, "/api/v1/users/me/avatar")
                        .file(new MockMultipartFile("file", "me.png", "image/png", png()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String url = set.get("avatarUrl").asString();
        String key = url.substring(url.lastIndexOf('/') + 1, url.length() - ".jpg".length());
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(
                Files.readAllBytes(Path.of("target/test-photos", key.substring(0, 2), key + ".jpg"))));
        assertThat(stored.getWidth()).isEqualTo(stored.getHeight()).isEqualTo(200);

        // Anyone (no sign-in) sees the username and the photo, never the real name or e-mail
        String profile = mvc.perform(get("/api/v1/users/" + username.toUpperCase() + "/profile"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode body = read(profile);
        assertThat(body.get("username").asString()).isEqualTo(username);
        assertThat(body.get("avatarUrl").asString()).isEqualTo(url);
        assertThat(body.get("photoCount").asInt()).isZero();
        assertThat(profile).doesNotContain("Ayşe", "Yılmaz", "avatar-test-");

        mvc.perform(delete("/api/v1/users/me/avatar").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(read(mvc.perform(get("/api/v1/users/" + username + "/profile")).andReturn().getResponse()
                .getContentAsString()).get("avatarUrl").isNull()).isTrue();
        assertThat(Path.of("target/test-photos", key.substring(0, 2), key + ".jpg")).doesNotExist();
    }

    @Test
    void anUnknownUserHasNoProfileAndOnlyAdminsRemoveSomeoneElsesPhoto() throws Exception {
        mvc.perform(get("/api/v1/users/nobody_here_xyz/profile")).andExpect(status().isNotFound());

        String username = "av_" + UUID.randomUUID().toString().substring(0, 6);
        String token = signedUp(username).get("accessToken").asString();
        mvc.perform(delete("/api/v1/admin/users/" + username + "/avatar").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
