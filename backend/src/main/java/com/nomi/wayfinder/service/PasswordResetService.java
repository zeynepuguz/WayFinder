package com.nomi.wayfinder.service;

import com.nomi.wayfinder.dto.AuthDtos.AuthResponse;
import com.nomi.wayfinder.entity.PasswordResetCode;
import com.nomi.wayfinder.entity.User;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.repository.PasswordResetCodeRepository;
import com.nomi.wayfinder.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * "Forgot password": a 6-digit code is e-mailed, the user sends it back with a new password.
 * - The same response is returned whether or not the e-mail has an account (no account probing).
 * - Only a hash of the code is stored; a code works once, for 15 minutes, with 5 guesses.
 * - A new code can be requested once a minute; it replaces the previous one.
 */
@Service
public class PasswordResetService {

    static final Duration CODE_TTL = Duration.ofMinutes(15);
    static final Duration RESEND_AFTER = Duration.ofMinutes(1);
    static final int MAX_ATTEMPTS = 5;

    private static final String INVALID_CODE = "Invalid or expired code";

    private final SecureRandom random = new SecureRandom();
    private final UserRepository userRepository;
    private final PasswordResetCodeRepository codeRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailService mailService;
    private final UserService userService;
    private final Clock clock;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetCodeRepository codeRepository,
            PasswordEncoder passwordEncoder,
            MailService mailService,
            UserService userService,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.codeRepository = codeRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailService = mailService;
        this.userService = userService;
        this.clock = clock;
    }

    @Transactional
    public void requestCode(String email) {
        Optional<User> user = userRepository.findByEmailIgnoreCase(email.trim());
        if (user.isEmpty()) {
            return;
        }
        Long userId = user.get().getId();
        Instant now = clock.instant();

        boolean sentRecently = codeRepository.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .filter(c -> c.getCreatedAt().plus(RESEND_AFTER).isAfter(now))
                .isPresent();
        if (sentRecently) {
            return;
        }

        String code = "%06d".formatted(random.nextInt(1_000_000));
        codeRepository.deleteByUserId(userId);
        codeRepository.save(new PasswordResetCode(userId, passwordEncoder.encode(code), now, now.plus(CODE_TTL)));
        mailService.sendPasswordResetCode(user.get().getEmail(), code, CODE_TTL.toMinutes());
    }

    // noRollbackFor: a wrong guess must still be counted
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse resetPassword(String email, String code, String newPassword) {
        User user = userRepository.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, INVALID_CODE));
        Instant now = clock.instant();

        PasswordResetCode resetCode = codeRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())
                .filter(c -> c.isUsable(now, MAX_ATTEMPTS))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, INVALID_CODE));

        if (!passwordEncoder.matches(code.trim(), resetCode.getCodeHash())) {
            resetCode.recordFailedAttempt();
            codeRepository.save(resetCode);
            throw new BusinessException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }

        resetCode.markUsed(now);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return userService.toAuthResponse(user);
    }
}
