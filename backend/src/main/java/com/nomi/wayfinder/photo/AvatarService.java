package com.nomi.wayfinder.photo;

import com.nomi.wayfinder.entity.User;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.photo.PhotoDtos.PublicProfile;
import com.nomi.wayfinder.repository.UserRepository;
import com.nomi.wayfinder.service.Usernames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Profile photos (optional) and the public profile other users see: username, profile photo, the photos the user
 * added. Profile photos are not checked by the AI (faces are expected here); an admin can remove one.
 */
@Service
public class AvatarService {

    static final int PROFILE_PHOTOS = 12;

    private final UserRepository users;
    private final PhotoImageProcessor images;
    private final PhotoStorage storage;
    private final UserPhotoRepository photos;
    private final PhotoService photoService;

    public AvatarService(UserRepository users, PhotoImageProcessor images, PhotoStorage storage,
                         UserPhotoRepository photos, PhotoService photoService) {
        this.users = users;
        this.images = images;
        this.storage = storage;
        this.photos = photos;
        this.photoService = photoService;
    }

    /** A new profile photo (square, without metadata); the old files are deleted. Returns its URL. */
    @Transactional
    public String set(long userId, byte[] bytes) {
        User user = find(userId);
        PhotoImageProcessor.ProcessedPhoto avatar = images.processAvatar(bytes);
        String key = storage.save(avatar.jpeg(), avatar.thumbnail());
        String old = user.getAvatarKey();
        user.setAvatarKey(key);
        users.save(user);
        storage.delete(old);
        return storage.url(key);
    }

    @Transactional
    public void remove(long userId) {
        clear(find(userId));
    }

    /** An admin removes someone's profile photo (an unsuitable one). */
    @Transactional
    public void removeOf(String username) {
        clear(byUsername(username));
    }

    @Transactional(readOnly = true)
    public PublicProfile profile(String username) {
        User user = byUsername(username);
        return new PublicProfile(user.getUsername(), storage.url(user.getAvatarKey()),
                YearMonth.from(user.getCreatedAt().atZone(ZoneOffset.UTC)).toString(),
                photos.countApprovedOfUser(user.getId()),
                photos.approvedOfUser(user.getId(), PROFILE_PHOTOS).stream().map(photoService::toResponse).toList());
    }

    private void clear(User user) {
        String old = user.getAvatarKey();
        if (old != null) {
            user.setAvatarKey(null);
            users.save(user);
            storage.delete(old);
        }
    }

    private User find(long userId) {
        return users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    // Unverified sign-ups have no public profile
    private User byUsername(String username) {
        return users.findByUsername(Usernames.normalize(username))
                .filter(User::isEmailVerified)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
