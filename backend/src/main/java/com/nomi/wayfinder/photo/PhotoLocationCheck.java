package com.nomi.wayfinder.photo;

import com.nomi.wayfinder.photo.PhotoImageProcessor.PhotoMetadata;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Step one of the verification: was the photo taken at the place / in the district?
 * The position comes from the photo's EXIF GPS, else from the phone's position at upload time (only when it is
 * precise enough). The position itself is never stored, only where it came from and the distance.
 */
public final class PhotoLocationCheck {

    // A device fix less precise than this proves nothing (cell tower / Wi-Fi guesses)
    static final double MAX_DEVICE_ACCURACY_METERS = 150;
    // Place: within this distance of the place's point, plus the fix's inaccuracy (at most 100 m of it)
    static final double PLACE_RADIUS_METERS = 250;
    static final double MAX_ACCURACY_ALLOWANCE_METERS = 100;
    // District: inside the polygon or at most this far outside it (border streets, the shore)
    static final double DISTRICT_BUFFER_METERS = 200;
    // Photos taken longer ago than this no longer show how the place looks
    static final int MAX_AGE_YEARS = 3;

    private PhotoLocationCheck() {
    }

    /**
     * @param accuracyMeters 0 for EXIF GPS (the camera does not say reliably)
     */
    public record Proof(ProofSource source, double latitude, double longitude, double accuracyMeters) {
    }

    /** EXIF GPS first; else the device position if it is valid and precise enough; else null (NO_LOCATION). */
    public static Proof proof(PhotoMetadata metadata, Double deviceLatitude, Double deviceLongitude,
                              Double deviceAccuracy) {
        if (metadata != null && metadata.hasGps()) {
            return new Proof(ProofSource.EXIF, metadata.latitude(), metadata.longitude(), 0);
        }
        if (deviceLatitude == null || deviceLongitude == null || deviceAccuracy == null
                || !PhotoImageProcessor.validCoordinates(deviceLatitude, deviceLongitude)
                || !Double.isFinite(deviceAccuracy) || deviceAccuracy < 0
                || deviceAccuracy > MAX_DEVICE_ACCURACY_METERS) {
            return null;
        }
        return new Proof(ProofSource.DEVICE, deviceLatitude, deviceLongitude, deviceAccuracy);
    }

    public static boolean nearPlace(Proof proof, double distanceMeters) {
        return distanceMeters <= PLACE_RADIUS_METERS + Math.min(proof.accuracyMeters(), MAX_ACCURACY_ALLOWANCE_METERS);
    }

    /** @param distanceToPolygonMeters 0 inside the district, null when the district has no polygon */
    public static boolean nearDistrict(Double distanceToPolygonMeters) {
        return distanceToPolygonMeters != null && distanceToPolygonMeters <= DISTRICT_BUFFER_METERS;
    }

    /** EXIF DateTimeOriginal more than 3 years ago; no date = not stale (the AI check still runs). */
    public static boolean stale(Instant takenAt, Instant now, ZoneId zone) {
        if (takenAt == null) {
            return false;
        }
        return takenAt.isBefore(now.atZone(zone).minusYears(MAX_AGE_YEARS).toInstant());
    }
}
