package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.dto.AuthDtos.PreferencesRequest;
import com.nomi.wayfinder.dto.AuthDtos.PreferencesResponse;
import com.nomi.wayfinder.dto.AuthDtos.UsernameRequest;
import com.nomi.wayfinder.dto.AuthDtos.UserResponse;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.photo.AvatarService;
import com.nomi.wayfinder.photo.PhotoRejectedException;
import com.nomi.wayfinder.photo.RejectReason;
import com.nomi.wayfinder.security.CurrentUser;
import com.nomi.wayfinder.security.JwtService;
import com.nomi.wayfinder.security.SessionService;
import com.nomi.wayfinder.security.SessionService.SessionView;
import com.nomi.wayfinder.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    private final UserService userService;
    private final SessionService sessions;
    private final AvatarService avatars;

    public UserController(UserService userService, SessionService sessions, AvatarService avatars) {
        this.userService = userService;
        this.sessions = sessions;
        this.avatars = avatars;
    }

    @GetMapping
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.getProfile(CurrentUser.id(jwt));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@AuthenticationPrincipal Jwt jwt) {
        userService.deleteAccount(CurrentUser.id(jwt));
    }

    // Open sessions (devices signed in to the account), this one marked current
    @GetMapping("/sessions")
    public List<SessionView> sessions(@AuthenticationPrincipal Jwt jwt) {
        return sessions.list(CurrentUser.id(jwt), sessionId(jwt));
    }

    // Signs one device out; its access token stops working at once
    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endSession(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        if (!sessions.endOne(CurrentUser.id(jwt), id)) {
            throw new ResourceNotFoundException("Session not found: " + id);
        }
    }

    // "Diğer tüm cihazlardan çıkış yap": every session but this one
    @PostMapping("/sessions/end-others")
    public Map<String, Integer> endOtherSessions(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("ended", sessions.endOthers(CurrentUser.id(jwt), sessionId(jwt)));
    }

    // A new public username (shown under the user's photos)
    @PutMapping("/username")
    public UserResponse changeUsername(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UsernameRequest request) {
        return userService.changeUsername(CurrentUser.id(jwt), request.username());
    }

    // Optional profile photo (JPEG / PNG / WebP): stored square, without metadata; shown with the username
    @PutMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> setAvatar(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file)
            throws java.io.IOException {
        if (file == null || file.isEmpty()) {
            throw new PhotoRejectedException(RejectReason.UNSUPPORTED);
        }
        return Map.of("avatarUrl", avatars.set(CurrentUser.id(jwt), file.getBytes()));
    }

    @DeleteMapping("/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAvatar(@AuthenticationPrincipal Jwt jwt) {
        avatars.remove(CurrentUser.id(jwt));
    }

    @PutMapping("/preferences")
    public PreferencesResponse updatePreferences(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PreferencesRequest request
    ) {
        return userService.updatePreferences(CurrentUser.id(jwt), request);
    }

    // Tokens from before sessions had ids have none
    private static Long sessionId(Jwt jwt) {
        Object sid = jwt.getClaim(JwtService.SESSION_CLAIM);
        return sid instanceof Number n ? n.longValue() : null;
    }
}
