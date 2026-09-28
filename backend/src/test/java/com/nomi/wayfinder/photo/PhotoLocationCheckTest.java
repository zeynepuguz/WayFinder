package com.nomi.wayfinder.photo;

import com.nomi.wayfinder.photo.PhotoImageProcessor.PhotoMetadata;
import com.nomi.wayfinder.photo.PhotoLocationCheck.Proof;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PhotoLocationCheckTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final PhotoMetadata EXIF_GPS = new PhotoMetadata(41.0086, 28.9802, null, 1);

    @Test
    void exifGpsWinsOverTheDevicePosition() {
        Proof proof = PhotoLocationCheck.proof(EXIF_GPS, 40.99, 29.03, 10.0);

        assertThat(proof.source()).isEqualTo(ProofSource.EXIF);
        assertThat(proof.latitude()).isEqualTo(41.0086);
        assertThat(proof.accuracyMeters()).isZero();
    }

    @Test
    void devicePositionCountsOnlyWhenPreciseEnough() {
        assertThat(PhotoLocationCheck.proof(PhotoMetadata.NONE, 40.99, 29.03, 150.0).source())
                .isEqualTo(ProofSource.DEVICE);
        assertThat(PhotoLocationCheck.proof(PhotoMetadata.NONE, 40.99, 29.03, 151.0)).isNull();
        assertThat(PhotoLocationCheck.proof(PhotoMetadata.NONE, 40.99, 29.03, null)).isNull();
        assertThat(PhotoLocationCheck.proof(PhotoMetadata.NONE, null, 29.03, 5.0)).isNull();
        assertThat(PhotoLocationCheck.proof(PhotoMetadata.NONE, 95.0, 29.03, 5.0)).isNull();
        assertThat(PhotoLocationCheck.proof(PhotoMetadata.NONE, 40.99, 29.03, -1.0)).isNull();
        assertThat(PhotoLocationCheck.proof(PhotoMetadata.NONE, 40.99, 29.03, Double.NaN)).isNull();
    }

    @Test
    void placeRadiusGrowsWithTheFixInaccuracyUpTo100Meters() {
        Proof exif = new Proof(ProofSource.EXIF, 0, 0, 0);
        Proof device40 = new Proof(ProofSource.DEVICE, 0, 0, 40);
        Proof device150 = new Proof(ProofSource.DEVICE, 0, 0, 150);

        assertThat(PhotoLocationCheck.nearPlace(exif, 250)).isTrue();
        assertThat(PhotoLocationCheck.nearPlace(exif, 251)).isFalse();
        assertThat(PhotoLocationCheck.nearPlace(device40, 290)).isTrue();
        assertThat(PhotoLocationCheck.nearPlace(device40, 291)).isFalse();
        assertThat(PhotoLocationCheck.nearPlace(device150, 350)).isTrue();
        assertThat(PhotoLocationCheck.nearPlace(device150, 351)).isFalse();
    }

    @Test
    void districtAllowsA200MeterBorder() {
        assertThat(PhotoLocationCheck.nearDistrict(0.0)).isTrue();
        assertThat(PhotoLocationCheck.nearDistrict(200.0)).isTrue();
        assertThat(PhotoLocationCheck.nearDistrict(201.0)).isFalse();
        // No polygon: cannot be verified
        assertThat(PhotoLocationCheck.nearDistrict(null)).isFalse();
    }

    @Test
    void photosOlderThanThreeYearsAreStale() {
        Instant now = LocalDateTime.of(2026, 9, 28, 12, 0).atZone(ISTANBUL).toInstant();

        assertThat(PhotoLocationCheck.stale(null, now, ISTANBUL)).isFalse();
        assertThat(PhotoLocationCheck.stale(LocalDateTime.of(2023, 10, 1, 0, 0).atZone(ISTANBUL).toInstant(),
                now, ISTANBUL)).isFalse();
        assertThat(PhotoLocationCheck.stale(LocalDateTime.of(2023, 9, 27, 0, 0).atZone(ISTANBUL).toInstant(),
                now, ISTANBUL)).isTrue();
    }

    @Test
    void haversineDistance() {
        // Galata Tower -> Hagia Sophia is about 2 km as the crow flies
        double meters = PhotoLocationCheck.distanceMeters(41.0256, 28.9742, 41.0086, 28.9802);

        assertThat(meters).isCloseTo(1_960, within(80.0));
        assertThat(PhotoLocationCheck.distanceMeters(41.0, 29.0, 41.0, 29.0)).isZero();
        // 0.001 degrees of latitude ~ 111 m
        assertThat(PhotoLocationCheck.distanceMeters(41.0, 29.0, 41.001, 29.0)).isCloseTo(111.2, within(0.5));
    }
}
