package com.nomi.wayfinder.service;

import com.nomi.wayfinder.billing.BillingService;
import com.nomi.wayfinder.config.NomiProperties;
import com.nomi.wayfinder.dto.AuthDtos.*;
import com.nomi.wayfinder.entity.User;
import com.nomi.wayfinder.entity.UserPreferences;
import com.nomi.wayfinder.entity.UserRole;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.photo.PhotoService;
import com.nomi.wayfinder.repository.UserPreferencesRepository;
import com.nomi.wayfinder.repository.UserRepository;
import com.nomi.wayfinder.security.EmailCodeService;
import com.nomi.wayfinder.security.EmailCodeService.Purpose;
import com.nomi.wayfinder.security.JwtService;
import com.nomi.wayfinder.security.LoginAttemptLimiter;
import com.nomi.wayfinder.security.SessionService;
import com.nomi.wayfinder.security.SessionService.Client;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final BillingService billingService;
    private final PhotoService photoService;
    private final EmailCodeService emailCodes;
    private final SessionService sessions;
    private final LoginAttemptLimiter loginAttempts;
    private final MailService mailService;
    private final Clock clock;
    // The owner's accounts (OWNER_EMAILS in .env): ADMIN when they sign up or sign in with a proven e-mail
    private final Set<String> ownerEmails;

    static final String INVALID_CODE = "Invalid or expired code";
    // Resend only works this long after step one (password / sign-up form) passed
    static final Duration RESEND_WINDOW = Duration.ofMinutes(30);
    // "8 Ekim 2026 18:47" (Istanbul time) in the new device e-mail
    private static final DateTimeFormatter SIGN_IN_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", Locale.forLanguageTag("tr"));

    public UserService(
            UserRepository userRepository,
            UserPreferencesRepository preferencesRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            BillingService billingService,
            PhotoService photoService,
            EmailCodeService emailCodes,
            SessionService sessions,
            LoginAttemptLimiter loginAttempts,
            MailService mailService,
            Clock clock,
            NomiProperties properties
    ) {
        this.emailCodes = emailCodes;
        this.sessions = sessions;
        this.loginAttempts = loginAttempts;
        this.mailService = mailService;
        this.clock = clock;
        List<String> owners = properties.security() == null ? null : properties.security().ownerEmails();
        this.ownerEmails = (owners == null ? List.<String>of() : owners).stream()
                .map(e -> e.trim().toLowerCase(Locale.ROOT)).filter(e -> !e.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.userRepository = userRepository;
        this.preferencesRepository = preferencesRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.billingService = billingService;
        this.photoService = photoService;
    }

    /**
     * Sign-up step one: the account is created unverified and a code goes to the e-mail. Signing up again before the
     * code was entered replaces the name and password (only the e-mail's owner can finish it with the code).
     */
    @Transactional
    public CodeSentResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user != null && user.isEmailVerified()) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email is already registered");
        }
        if (user == null) {
            user = createUser(email, request.password(), request.firstName().trim(), request.lastName().trim(),
                    UserRole.USER, null);
        } else {
            user.setName(request.firstName().trim(), request.lastName().trim());
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            userRepository.save(user);
        }
        sendCode(user, Purpose.SIGN_UP);
        return codeSent(email);
    }

    // noRollbackFor: a wrong guess must still be counted
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse verifySignUp(VerifyCodeRequest request, Client client) {
        return verify(request, Purpose.SIGN_UP, client);
    }

    /**
     * @param verifiedAt null = the e-mail still has to be proven with the sign-up code
     */
    @Transactional
    public User createUser(String email, String password, String firstName, String lastName, UserRole role,
                           Instant verifiedAt) {
        String normalizedEmail = normalize(email);

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email is already registered");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setName(firstName, lastName);
        user.setRole(role);
        if (verifiedAt != null) {
            user.markEmailVerified(verifiedAt);
        }
        User saved = userRepository.save(user);

        preferencesRepository.save(new UserPreferences(saved.getId()));
        return saved;
    }

    /**
     * Sign-in step one: the right password sends a code to the e-mail; only the code signs in. So knowing the
     * password is not enough to get into an account (also the owner's admin account).
     */
    @Transactional
    public CodeSentResponse login(LoginRequest request) {
        String email = normalize(request.email());
        if (loginAttempts.locked(email)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts, try again in 15 minutes");
        }
        // Same message for unknown email and wrong password, so emails cannot be probed
        User user = userRepository.findByEmailIgnoreCase(email)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElse(null);
        if (user == null) {
            loginAttempts.failed(email);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }
        loginAttempts.succeeded(email);
        // An account whose sign-up code was never entered finishes the sign-up instead
        Purpose purpose = user.isEmailVerified() ? Purpose.SIGN_IN : Purpose.SIGN_UP;
        sendCode(user, purpose);
        return codeSent(email);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse verifySignIn(VerifyCodeRequest request, Client client) {
        return verify(request, Purpose.SIGN_IN, client);
    }

    /**
     * A new code, at most once a minute, only while a sign-up / sign-in is running (step one passed within the
     * last half hour): nobody can make Nomi mail codes to an address. Always 202.
     */
    @Transactional
    public void resendCode(ResendCodeRequest request) {
        userRepository.findByEmailIgnoreCase(normalize(request.email()))
                .filter(user -> emailCodes.sentWithin(user.getId(), request.purpose(), RESEND_WINDOW))
                .ifPresent(user -> sendCode(user, request.purpose()));
    }

    private AuthResponse verify(VerifyCodeRequest request, Purpose purpose, Client client) {
        User user = userRepository.findByEmailIgnoreCase(normalize(request.email()))
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, INVALID_CODE));
        // A not yet verified account signs in with its sign-up code
        Purpose expected = user.isEmailVerified() ? purpose : Purpose.SIGN_UP;
        if (!emailCodes.verify(user.getId(), expected, request.code())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, INVALID_CODE);
        }
        user.markEmailVerified(clock.instant());
        userRepository.save(user);
        return signIn(promoteOwner(user), client);
    }

    private void sendCode(User user, Purpose purpose) {
        emailCodes.send(user.getId(), purpose, code -> {
            long minutes = EmailCodeService.CODE_TTL.toMinutes();
            if (purpose == Purpose.SIGN_UP) {
                String name = user.getFirstName() != null ? user.getFirstName() : user.getDisplayName();
                mailService.sendSignUpCode(user.getEmail(), name, code, minutes);
            } else {
                mailService.sendSignInCode(user.getEmail(), code, minutes);
            }
        });
    }

    private static CodeSentResponse codeSent(String email) {
        return new CodeSentResponse(maskEmail(email), EmailCodeService.CODE_TTL.toMinutes(),
                EmailCodeService.RESEND_AFTER.toSeconds());
    }

    // "zeynep@gmail.com" -> "ze****@gmail.com": the user sees where the code went, a screenshot does not leak it
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        String local = email.substring(0, at);
        String shown = local.substring(0, local.length() > 2 ? 2 : 1);
        return shown + "****" + email.substring(at);
    }

    /** POST /auth/refresh: a new token pair for a valid refresh token; the session runs 7 more days. */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse refresh(String refreshToken) {
        SessionService.Issued session = sessions.refresh(refreshToken);
        User user = userRepository.findById(session.userId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, SessionService.SIGN_IN_AGAIN));
        return toAuthResponse(user, session);
    }

    public void logout(String refreshToken) {
        sessions.end(refreshToken);
    }

    // An owner account becomes ADMIN (admin area, place reviews) once its e-mail is proven; the role is in the
    // token from this sign-in on
    User promoteOwner(User user) {
        if (user.getRole() != UserRole.ADMIN && user.isEmailVerified()
                && ownerEmails.contains(user.getEmail().toLowerCase(Locale.ROOT))) {
            user.setRole(UserRole.ADMIN);
            return userRepository.save(user);
        }
        return user;
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(Long userId) {
        return toUserResponse(getUser(userId));
    }

    @Transactional
    public PreferencesResponse updatePreferences(Long userId, PreferencesRequest request) {
        UserPreferences preferences = getPreferences(userId);

        preferences.setWalkingTolerance(request.walkingTolerance());
        preferences.setDefaultPartySize(request.defaultPartySize());
        preferences.setDefaultBudget(request.defaultBudget());
        preferences.setInterests(Interests.normalize(request.interests()));

        return toPreferencesResponse(preferencesRepository.save(preferences));
    }

    // Google Play requires in-app account deletion. Routes, messages, saved places, passes and photo rows
    // cascade; the photo files are removed first
    @Transactional
    public void deleteAccount(Long userId) {
        User user = getUser(userId);
        photoService.deleteFilesOfUser(userId);
        userRepository.delete(user);
    }

    @Transactional
    public UserPreferences getPreferences(Long userId) {
        return preferencesRepository.findById(userId)
                .orElseGet(() -> preferencesRepository.save(new UserPreferences(userId)));
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));
    }

    // Signs the user in on this device: a new session (7 days while used), JWT + profile. A device the account has
    // not used lately gets a "new device" e-mail (not the first sign-in after sign-up)
    public AuthResponse signIn(User user, Client client) {
        boolean newDevice = sessions.isNewDevice(user.getId(), client.device());
        SessionService.Issued session = sessions.start(user.getId(), client);
        if (newDevice) {
            String when = SIGN_IN_TIME.format(clock.instant().atZone(clock.getZone()));
            mailService.sendNewDeviceAlert(user.getEmail(), client.device(), client.ip(), when);
        }
        return toAuthResponse(user, session);
    }

    private AuthResponse toAuthResponse(User user, SessionService.Issued session) {
        JwtService.IssuedToken token = jwtService.issue(user, session.sessionId());
        return new AuthResponse(token.value(), "Bearer", token.expiresAt(), session.refreshToken(),
                session.expiresAt(), toUserResponse(user));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                toPreferencesResponse(getPreferences(user.getId())),
                billingService.status(user.getId())
        );
    }

    private PreferencesResponse toPreferencesResponse(UserPreferences preferences) {
        return new PreferencesResponse(
                preferences.getWalkingTolerance(),
                preferences.getDefaultPartySize(),
                preferences.getDefaultBudget(),
                preferences.getInterests()
        );
    }
}
