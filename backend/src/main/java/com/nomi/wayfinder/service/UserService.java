package com.nomi.wayfinder.service;

import com.nomi.wayfinder.billing.BillingService;
import com.nomi.wayfinder.dto.AuthDtos.*;
import com.nomi.wayfinder.entity.User;
import com.nomi.wayfinder.entity.UserPreferences;
import com.nomi.wayfinder.entity.UserRole;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.repository.UserPreferencesRepository;
import com.nomi.wayfinder.repository.UserRepository;
import com.nomi.wayfinder.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final BillingService billingService;

    public UserService(
            UserRepository userRepository,
            UserPreferencesRepository preferencesRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            BillingService billingService
    ) {
        this.userRepository = userRepository;
        this.preferencesRepository = preferencesRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.billingService = billingService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        User user = createUser(request.email(), request.password(), request.displayName(), UserRole.USER);
        return toAuthResponse(user);
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

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Same message for unknown email and wrong password, so emails cannot be probed
        User user = userRepository.findByEmailIgnoreCase(request.email().trim())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        return toAuthResponse(user);
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

    // Google Play requires in-app account deletion. Routes, messages, saved places and passes cascade.
    @Transactional
    public void deleteAccount(Long userId) {
        userRepository.delete(getUser(userId));
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
