package com.nomi.wayfinder.controller;

import com.nomi.wayfinder.photo.PhotoDtos.MyPhotoResponse;
import com.nomi.wayfinder.photo.PhotoDtos.PhotoResponse;
import com.nomi.wayfinder.photo.PhotoDtos.UploadResponse;
import com.nomi.wayfinder.photo.PhotoRejectedException;
import com.nomi.wayfinder.photo.PhotoService;
import com.nomi.wayfinder.photo.RejectReason;
import com.nomi.wayfinder.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * User photos of places and districts ("Kullanıcılarımızdan fotoğraflar").
 * Uploading needs a login (any user, not only Premium); the collages are public.
 */
@RestController
@RequestMapping("/api/v1")
public class PhotoController {

    private final PhotoService photoService;

    public PhotoController(PhotoService photoService) {
        this.photoService = photoService;
    }

    /**
     * multipart/form-data: file (JPEG / PNG / WebP, max 12 MB) and, optionally, the phone's position when
     * uploading (latitude, longitude, accuracy in meters). Needed when the photo has no GPS in its EXIF.
     */
    @PostMapping(value = "/places/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UploadResponse uploadPlacePhoto(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) Double accuracy
    ) throws IOException {
        return photoService.uploadForPlace(CurrentUser.id(jwt), id, bytes(file), latitude, longitude, accuracy);
    }

    @PostMapping(value = "/cities/{city}/districts/{district}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UploadResponse uploadDistrictPhoto(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String city,
            @PathVariable String district,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) Double accuracy
    ) throws IOException {
        long districtId = photoService.requireDistrictId(city, district);
        return photoService.uploadForDistrict(CurrentUser.id(jwt), districtId, bytes(file), latitude, longitude,
                accuracy);
    }

    // The latest 10 approved photos, newest first
    @GetMapping("/places/{id}/photos")
    public List<PhotoResponse> placePhotos(@PathVariable Long id) {
        return photoService.placePhotos(id);
    }

    // The district's own photos and those of the places inside it, latest 10
    @GetMapping("/cities/{city}/districts/{district}/photos")
    public List<PhotoResponse> districtPhotos(@PathVariable String city, @PathVariable String district) {
        return photoService.districtPhotos(city, district);
    }

    // The user's own uploads, every status, newest 50
    @GetMapping("/me/photos")
    public List<MyPhotoResponse> myPhotos(@AuthenticationPrincipal Jwt jwt) {
        return photoService.myPhotos(CurrentUser.id(jwt));
    }

    @DeleteMapping("/photos/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto(@AuthenticationPrincipal Jwt jwt, Authentication authentication, @PathVariable Long id) {
        boolean admin = authentication.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        photoService.delete(CurrentUser.id(jwt), admin, id);
    }

    private static byte[] bytes(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new PhotoRejectedException(RejectReason.UNSUPPORTED);
        }
        return file.getBytes();
    }
}
