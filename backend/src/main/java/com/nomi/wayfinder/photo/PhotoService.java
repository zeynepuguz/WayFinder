package com.nomi.wayfinder.photo;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.exception.PlaceNotFoundException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.i18n.Texts;
import com.nomi.wayfinder.photo.PhotoDtos.MyPhotoResponse;
import com.nomi.wayfinder.photo.PhotoDtos.PhotoResponse;
import com.nomi.wayfinder.photo.PhotoDtos.Target;
import com.nomi.wayfinder.photo.PhotoDtos.UploadResponse;
import com.nomi.wayfinder.photo.PhotoImageProcessor.ProcessedPhoto;
import com.nomi.wayfinder.photo.PhotoLocationCheck.Proof;
import com.nomi.wayfinder.photo.UserPhotoRepository.NewPhoto;
import com.nomi.wayfinder.photo.UserPhotoRepository.PlaceTarget;
import com.nomi.wayfinder.photo.UserPhotoRepository.PublicPhoto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * "Kullanıcılarımızdan fotoğraflar": logged-in users add photos of a place or a district; only verified ones
 * are published (see PhotoLocationCheck and PhotoVerifier), and only the latest 10 per place / district are kept.
 */
@Service
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    static final int COLLAGE_SIZE = 10;
    static final int MY_PHOTOS_LIMIT = 50;

    private final UserPhotoRepository repository;
    private final PhotoImageProcessor images;
    private final PhotoStorage storage;
    private final PhotoUploadLimiter limiter;
    private final PhotoVerificationQueue queue;
    private final CityService cityService;
    private final DistrictService districtService;
    private final Clock clock;

    public PhotoService(UserPhotoRepository repository, PhotoImageProcessor images, PhotoStorage storage,
                        PhotoUploadLimiter limiter, PhotoVerificationQueue queue, CityService cityService,
                        DistrictService districtService, Clock clock) {
        this.repository = repository;
        this.images = images;
        this.storage = storage;
        this.limiter = limiter;
        this.queue = queue;
        this.cityService = cityService;
        this.districtService = districtService;
        this.clock = clock;
    }

    // ---------- targets ----------

    public long requireDistrictId(String citySlug, String districtSlug) {
        CityService.City city = cityService.findBySlug(citySlug)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + citySlug));
        return districtService.findIdBySlug(city.id(), districtSlug)
                .orElseThrow(() -> new ResourceNotFoundException("District not found: " + districtSlug));
    }

    // ---------- upload ----------

    public UploadResponse uploadForPlace(long userId, long placeId, byte[] file,
                                         Double latitude, Double longitude, Double accuracy) {
        PlaceTarget place = repository.findPlaceTarget(placeId).orElseThrow(() -> new PlaceNotFoundException(placeId));
        limiter.acquire(userId, PhotoTargetType.PLACE, placeId);
        ProcessedPhoto photo = images.process(file);

        Proof proof = PhotoLocationCheck.proof(photo.metadata(), latitude, longitude, accuracy);
        Double distance = proof == null ? null : PhotoLocationCheck.distanceMeters(
                proof.latitude(), proof.longitude(), place.latitude(), place.longitude());
        RejectReason reason = proof == null ? RejectReason.NO_LOCATION
                : !PhotoLocationCheck.nearPlace(proof, distance) ? RejectReason.NOT_NEAR
                : staleReason(photo);
        return store(userId, placeId, null, photo, proof, distance, reason);
    }

    public UploadResponse uploadForDistrict(long userId, long districtId, byte[] file,
                                            Double latitude, Double longitude, Double accuracy) {
        limiter.acquire(userId, PhotoTargetType.DISTRICT, districtId);
        ProcessedPhoto photo = images.process(file);

        Proof proof = PhotoLocationCheck.proof(photo.metadata(), latitude, longitude, accuracy);
        Double distance = proof == null ? null
                : repository.distanceToDistrict(districtId, proof.latitude(), proof.longitude());
        RejectReason reason = proof == null ? RejectReason.NO_LOCATION
                : !PhotoLocationCheck.nearDistrict(distance) ? RejectReason.NOT_NEAR
                : staleReason(photo);
        return store(userId, null, districtId, photo, proof, distance, reason);
    }

    private RejectReason staleReason(ProcessedPhoto photo) {
        return PhotoLocationCheck.stale(photo.metadata().takenAt(), clock.instant(), clock.getZone())
                ? RejectReason.NOT_RELEVANT : null;
    }

    private UploadResponse store(long userId, Long placeId, Long districtId, ProcessedPhoto photo, Proof proof,
                                 Double distance, RejectReason reason) {
        String key = storage.save(photo.jpeg(), photo.thumbnail());
        PhotoStatus status = reason == null ? PhotoStatus.PENDING : PhotoStatus.REJECTED;
        long id;
        try {
            id = repository.insert(new NewPhoto(userId, placeId, districtId, status, reason, key,
                    photo.width(), photo.height(), photo.metadata().takenAt(),
                    proof == null ? null : proof.source(), distance, clock.instant()));
        } catch (RuntimeException e) {
            storage.delete(key);
            throw e;
        }
        // Never the position itself, only where it came from and how far it was
        log.info("Photo {} uploaded for {} {}: {} (proof {}, distance {} m)", id,
                placeId != null ? "place" : "district", placeId != null ? placeId : districtId,
                reason == null ? "PENDING" : "REJECTED " + reason,
                proof == null ? "none" : proof.source(), distance == null ? "-" : Math.round(distance));

        if (status == PhotoStatus.PENDING) {
            queue.submit(id);
            return new UploadResponse(id, status.name(), null, null);
        }
        PhotoTargetType type = placeId != null ? PhotoTargetType.PLACE : PhotoTargetType.DISTRICT;
        return new UploadResponse(id, status.name(), reason.name(), reason.message(type, isStale(reason, null)));
    }

    // NOT_RELEVANT without an AI confidence can only come from the "taken years ago" check
    private static boolean isStale(RejectReason reason, Double aiConfidence) {
        return reason == RejectReason.NOT_RELEVANT && aiConfidence == null;
    }

    // ---------- collages ----------

    public List<PhotoResponse> placePhotos(long placeId) {
        if (repository.findPlaceTarget(placeId).isEmpty()) {
            throw new PlaceNotFoundException(placeId);
        }
        return repository.approvedOfPlace(placeId, COLLAGE_SIZE).stream().map(this::toResponse).toList();
    }

    public List<PhotoResponse> districtPhotos(String citySlug, String districtSlug) {
        long districtId = requireDistrictId(citySlug, districtSlug);
        return repository.approvedOfDistrict(districtId, COLLAGE_SIZE).stream().map(this::toResponse).toList();
    }

    private PhotoResponse toResponse(PublicPhoto photo) {
        return new PhotoResponse(photo.id(), storage.url(photo.fileKey()), storage.thumbUrl(photo.fileKey()),
                photo.width(), photo.height(), photo.createdAt(), uploaderName(photo.displayName()),
                photo.placeName() == null ? null : photo.placeId(), photo.placeName());
    }

    // Only the first name is public ("Zeynep Uğuz" -> "Zeynep")
    static String uploaderName(String displayName) {
        String first = displayName == null ? "" : displayName.trim().split("\\s+")[0];
        if (first.isEmpty()) {
            return Texts.t("Nomi kullanıcısı", "Nomi user");
        }
        return first.length() > 30 ? first.substring(0, 30) : first;
    }

    // ---------- the user's own photos ----------

    public List<MyPhotoResponse> myPhotos(long userId) {
        return repository.ofUser(userId, MY_PHOTOS_LIMIT).stream().map(photo -> {
            PhotoTargetType type = photo.districtTarget() ? PhotoTargetType.DISTRICT : PhotoTargetType.PLACE;
            Target target = type == PhotoTargetType.PLACE
                    ? new Target(type.name(), photo.placeId(), photo.placeName(), photo.citySlug(), photo.cityName(),
                    photo.districtSlug(), photo.districtName())
                    : new Target(type.name(), null, photo.districtName(), photo.citySlug(), photo.cityName(),
                    photo.districtSlug(), photo.districtName());
            RejectReason reason = photo.rejectReason();
            return new MyPhotoResponse(photo.id(), storage.url(photo.fileKey()), storage.thumbUrl(photo.fileKey()),
                    photo.status().name(), reason == null ? null : reason.name(),
                    reason == null ? null : reason.message(type, isStale(reason, photo.aiConfidence())),
                    target, photo.createdAt());
        }).toList();
    }

    // ---------- delete ----------

    /** The owner or an admin; anyone else gets 404 (does not reveal whose photo it is). */
    public void delete(long userId, boolean admin, long photoId) {
        UserPhotoRepository.PhotoRow photo = repository.findById(photoId)
                .filter(p -> admin || p.userId() == userId)
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found: " + photoId));
        repository.delete(photo.id());
        storage.delete(photo.fileKey());
    }

    /** Account deletion: the rows cascade with the user, the files are removed here. */
    public void deleteFilesOfUser(long userId) {
        repository.fileKeysOfUser(userId).forEach(storage::delete);
    }
}
