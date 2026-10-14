package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.photo.AvatarService;
import com.nomi.wayfinder.photo.PhotoDtos.PublicProfile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** What anyone sees of a user: username, profile photo, the photos they added (never the real name or e-mail). */
@RestController
@RequestMapping("/api/v1")
public class PublicProfileController {

    private final AvatarService avatars;

    public PublicProfileController(AvatarService avatars) {
        this.avatars = avatars;
    }

    @GetMapping("/users/{username}/profile")
    public PublicProfile profile(@PathVariable String username) {
        return avatars.profile(username);
    }

    // An unsuitable profile photo: an admin removes it
    @DeleteMapping("/admin/users/{username}/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAvatar(@PathVariable String username) {
        avatars.removeOf(username);
    }
}
