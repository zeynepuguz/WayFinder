package com.nomi.wayfinder.service;

import com.nomi.wayfinder.entity.PasswordResetCode;
import com.nomi.wayfinder.entity.User;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.repository.PasswordResetCodeRepository;
import com.nomi.wayfinder.repository.UserRepository;
import com.nomi.wayfinder.security.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T09:00:00Z");
    private static final SessionService.Client CLIENT = new SessionService.Client("test", "127.0.0.1");

    // Readable fake instead of BCrypt
    private static final PasswordEncoder ENCODER = new PasswordEncoder() {
        public String encode(CharSequence raw) {
            return "hash:" + raw;
        }

        public boolean matches(CharSequence raw, String encoded) {
            return encoded.equals("hash:" + raw);
        }
    };

    private final List<PasswordResetCode> codes = new ArrayList<>();
    private User user;
    private UserRepository users;
    private MailService mail;
    private UserService userService;
    private SessionService sessions;

    @BeforeEach
    void setUp() {
        user = new User();
        ReflectionTestUtils.setField(user, "id", 7L);
        user.setEmail("zeynep@example.com");
        user.setPasswordHash("hash:old-password");

        users = mock(UserRepository.class);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(users.findByEmailIgnoreCase("zeynep@example.com")).thenReturn(Optional.of(user));
        mail = mock(MailService.class);
        userService = mock(UserService.class);
        sessions = mock(SessionService.class);
    }

    private PasswordResetService service(Instant now) {
        PasswordResetCodeRepository repository = mock(PasswordResetCodeRepository.class);
        when(repository.save(any())).thenAnswer(inv -> {
            PasswordResetCode code = inv.getArgument(0);
            if (!codes.contains(code)) {
                codes.add(code);
            }
            return code;
        });
        doAnswer(inv -> {
            codes.clear();
            return null;
        }).when(repository).deleteByUserId(7L);
        when(repository.findFirstByUserIdOrderByCreatedAtDesc(7L)).thenAnswer(inv ->
                codes.stream().max(Comparator.comparing(PasswordResetCode::getCreatedAt)));
        return new PasswordResetService(users, repository, ENCODER, mail, userService, sessions,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private String requestAndCaptureCode(Instant now) {
        service(now).requestCode(" zeynep@example.com");
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail, atLeastOnce()).sendPasswordResetCode(eq("zeynep@example.com"), code.capture(), eq(15L));
        return code.getValue();
    }

    @Test
    void correctCodeSetsTheNewPasswordOnce() {
        String code = requestAndCaptureCode(NOW);
        assertThat(code).matches("\\d{6}");
        assertThat(codes.getFirst().getCodeHash()).isNotEqualTo(code);

        service(NOW.plusSeconds(60)).resetPassword("zeynep@example.com", code, "new-password", CLIENT);

        assertThat(user.getPasswordHash()).isEqualTo("hash:new-password");
        verify(userService).signIn(user, CLIENT);
        // Whoever knew the old password is signed out everywhere
        verify(sessions).endAll(7L);
        assertThatThrownBy(() -> service(NOW.plusSeconds(90)).resetPassword("zeynep@example.com", code, "again-123", CLIENT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unknownEmailGetsNoMailAndNoError() {
        service(NOW).requestCode("nobody@example.com");

        verifyNoInteractions(mail);
        assertThat(codes).isEmpty();
    }

    @Test
    void codeExpiresAfterFifteenMinutes() {
        String code = requestAndCaptureCode(NOW);

        assertThatThrownBy(() -> service(NOW.plus(Duration.ofMinutes(16)))
                .resetPassword("zeynep@example.com", code, "new-password", CLIENT))
                .isInstanceOf(BusinessException.class);
        assertThat(user.getPasswordHash()).isEqualTo("hash:old-password");
    }

    @Test
    void codeIsBurnedAfterFiveWrongGuesses() {
        String code = requestAndCaptureCode(NOW);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < PasswordResetService.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> service(NOW).resetPassword("zeynep@example.com", wrong, "new-password", CLIENT))
                    .isInstanceOf(BusinessException.class);
        }

        assertThatThrownBy(() -> service(NOW).resetPassword("zeynep@example.com", code, "new-password", CLIENT))
                .isInstanceOf(BusinessException.class);
        assertThat(user.getPasswordHash()).isEqualTo("hash:old-password");
    }

    @Test
    void newCodeOnlyAfterAMinuteAndItReplacesTheOldOne() {
        String first = requestAndCaptureCode(NOW);
        service(NOW.plusSeconds(30)).requestCode("zeynep@example.com");
        verify(mail, times(1)).sendPasswordResetCode(anyString(), anyString(), anyLong());

        service(NOW.plusSeconds(61)).requestCode("zeynep@example.com");
        verify(mail, times(2)).sendPasswordResetCode(anyString(), anyString(), anyLong());
        assertThat(codes).hasSize(1);

        if (!codes.getFirst().getCodeHash().equals("hash:" + first)) {
            assertThatThrownBy(() -> service(NOW.plusSeconds(70)).resetPassword("zeynep@example.com", first, "new-password", CLIENT))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
