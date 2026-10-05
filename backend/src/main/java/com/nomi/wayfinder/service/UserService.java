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
import com.nomi.wayfinder.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    // The owner's accounts (OWNER_EMAILS in .env): ADMIN when they register or sign in
    private final Set<String> ownerEmails;

    public UserService(
            UserRepository userRepository,
            UserPreferencesRepository preferencesRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            BillingService billingService,
            PhotoService photoService,
            NomiProperties properties
    ) {
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

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User user = createUser(request.email(), request.password(), request.displayName(), UserRole.USER);
        return toAuthResponse(promoteOwner(user));
    }

    @Transactional
    public User createUser(String email, String password, String displayName, UserRole role) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email is already registered");
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(displayName.trim());
        user.setRole(role);
        User saved = userRepository.save(user);

        preferencesRepository.save(new UserPreferences(saved.getId()));
        return saved;
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Same message for unknown email and wrong password, so emails cannot be probed
        User user = userRepository.findByEmailIgnoreCase(request.email().trim())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        return toAuthResponse(promoteOwner(user));
    }

    // An owner account becomes ADMIN (admin area, place reviews); the role is in the token from this sign-in on
    User promoteOwner(User user) {
        if (user.getRole() != UserRole.ADMIN && ownerEmails.contains(user.getEmail().toLowerCase(Locale.ROOT))) {
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

    // Signs the user in: new JWT + profile
    public AuthResponse toAuthResponse(User user) {
        JwtService.IssuedToken token = jwtService.issue(user);
        return new AuthResponse(token.value(), "Bearer", token.expiresAt(), toUserResponse(user));
    }

    private UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
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
