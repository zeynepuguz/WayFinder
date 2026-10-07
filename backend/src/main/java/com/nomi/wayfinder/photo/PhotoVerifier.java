package com.nomi.wayfinder.photo;

import com.nomi.wayfinder.photo.UserPhotoRepository.PhotoRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Step two of the verification, after the location proof passed at upload: the AI check (safe, shows the
 * place, people not in focus). Runs in the background (PhotoVerificationQueue). On any error the photo simply
 * stays PENDING and PhotoJobs retries it later; a photo is never approved without a verdict.
 */
@Component
public class PhotoVerifier {

    private static final Logger log = LoggerFactory.getLogger(PhotoVerifier.class);

    static final double MIN_CONFIDENCE = 0.6;

    private final UserPhotoRepository repository;
    private final PhotoStorage storage;
    private final PhotoImageProcessor images;
    private final PhotoAiClient ai;
    private final PhotoProperties properties;
    private final Clock clock;

    public PhotoVerifier(UserPhotoRepository repository, PhotoStorage storage, PhotoImageProcessor images,
                         PhotoAiClient ai, PhotoProperties properties, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.images = images;
        this.ai = ai;
        this.properties = properties;
        this.clock = clock;
    }

    /** Approve only a relevant, safe photo without people in focus, with confidence >= 0.6. */
    static Optional<RejectReason> decide(PhotoAiClient.Verdict verdict) {
        if (!verdict.safe()) {
            return Optional.of(RejectReason.UNSAFE);
        }
        if (verdict.peopleFocused()) {
            return Optional.of(RejectReason.PEOPLE);
        }
        if (!verdict.relevant() || verdict.confidence() < MIN_CONFIDENCE) {
            return Optional.of(RejectReason.NOT_RELEVANT);
        }
        return Optional.empty();
    }

    public void verify(long photoId) {
        PhotoRow photo = repository.findById(photoId).orElse(null);
        if (photo == null || photo.status() != PhotoStatus.PENDING || photo.fileKey() == null) {
            return;
        }
        if (!ai.isEnabled()) {
            log.debug("Photo {} stays pending: AI_SERVICE_URL is not set", photoId);
            return;
        }
        if (ai.budgetReached()) {
            log.debug("Photo {} stays pending: this month's AI budget is used up", photoId);
            return;
        }

        PhotoAiClient.Verdict verdict;
        try {
            PhotoAiClient.VerifyRequest request = request(photo);
            if (request == null) {
                // The place / district was deleted meanwhile; the row cascades away with it
                return;
            }
            verdict = ai.verify(request);
        } catch (Exception e) {
            // Status only for HTTP errors: an error body could echo the request (the photo) back
            String why = e instanceof org.springframework.web.client.RestClientResponseException http
                    ? "HTTP " + http.getStatusCode().value() : e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Photo {} AI check failed, retried later: {}", photoId, why);
            return;
        }

        Optional<RejectReason> rejection = decide(verdict);
        if (rejection.isPresent()) {
            if (repository.markRejected(photoId, rejection.get(), verdict.confidence(), clock.instant())) {
                log.info("Photo {} rejected: {} (confidence {})", photoId, rejection.get(), verdict.confidence());
            }
            return;
        }
        if (!repository.markApproved(photoId, verdict.confidence(), clock.instant())) {
            return;
        }
        log.info("Photo {} approved (confidence {})", photoId, verdict.confidence());

        long targetId = photo.placeId() != null ? photo.placeId() : photo.districtId();
        List<String> removed = repository.pruneApproved(photo.targetType(), targetId, properties.keepPerTarget());
        removed.forEach(storage::delete);
        if (!removed.isEmpty()) {
            log.info("Removed {} older photo(s) of {} {}", removed.size(),
                    photo.targetType().name().toLowerCase(Locale.ROOT), targetId);
        }
    }

    private PhotoAiClient.VerifyRequest request(PhotoRow photo) throws java.io.IOException {
        String image = Base64.getEncoder().encodeToString(images.downscaleForAi(storage.readMain(photo.fileKey())));
        if (photo.placeId() != null) {
            return repository.findPlaceTarget(photo.placeId())
                    .map(place -> new PhotoAiClient.VerifyRequest(image, PhotoTargetType.PLACE.name(), place.name(),
                            place.category(), place.cityName(), place.districtName(), place.imageUrl()))
                    .orElse(null);
        }
        return repository.findDistrictTarget(photo.districtId())
                .map(district -> new PhotoAiClient.VerifyRequest(image, PhotoTargetType.DISTRICT.name(),
                        district.name(), null, district.cityName(), district.name(), null))
                .orElse(null);
    }
}
