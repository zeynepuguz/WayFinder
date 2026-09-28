package com.nomi.wayfinder.photo;

import com.nomi.wayfinder.photo.PhotoAiClient.Verdict;
import com.nomi.wayfinder.photo.PhotoAiClient.VerifyRequest;
import com.nomi.wayfinder.photo.UserPhotoRepository.DistrictTarget;
import com.nomi.wayfinder.photo.UserPhotoRepository.PhotoRow;
import com.nomi.wayfinder.photo.UserPhotoRepository.PlaceTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// The AI part of the verification; the AI service client is always a mock (no network, no OpenAI)
class PhotoVerifierTest {

    private static final Instant NOW = Instant.parse("2026-09-28T09:00:00Z");
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private UserPhotoRepository repository;
    private PhotoStorage storage;
    private PhotoImageProcessor images;
    private PhotoAiClient ai;
    private PhotoVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(UserPhotoRepository.class);
        storage = mock(PhotoStorage.class);
        images = mock(PhotoImageProcessor.class);
        ai = mock(PhotoAiClient.class);
        when(ai.isEnabled()).thenReturn(true);
        when(storage.readMain(anyString())).thenReturn(new byte[]{1, 2, 3});
        when(images.downscaleForAi(any())).thenReturn(new byte[]{4, 5, 6});
        when(repository.findPlaceTarget(7L)).thenReturn(Optional.of(new PlaceTarget(7, "Galata Kulesi", "ATTRACTION",
                41.0256, 28.9742, "İstanbul", "Beyoğlu", "https://upload.wikimedia.org/galata.jpg")));
        when(repository.findDistrictTarget(3L)).thenReturn(Optional.of(new DistrictTarget(3, "Kadıköy", "İstanbul")));
        when(repository.markApproved(anyLong(), anyDouble(), any())).thenReturn(true);
        when(repository.markRejected(anyLong(), any(), any(), any())).thenReturn(true);
        PhotoProperties properties = new PhotoProperties("x", "/media/photos", 10, 10, 3, Duration.ofSeconds(60));
        verifier = new PhotoVerifier(repository, storage, images, ai, properties, Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    private void pendingPlacePhoto(long id) {
        when(repository.findById(id)).thenReturn(Optional.of(new PhotoRow(id, 1, 7L, null, PhotoStatus.PENDING,
                null, KEY, 1600, 1200, null, NOW)));
    }

    private static Verdict verdict(boolean relevant, boolean safe, boolean people, double confidence) {
        return new Verdict(relevant, safe, people, confidence, "test");
    }

    @Test
    void relevantSafePhotoIsApprovedAndOnlyTheLatestTenAreKept() {
        pendingPlacePhoto(5);
        when(ai.verify(any())).thenReturn(verdict(true, true, false, 0.9));
        when(repository.pruneApproved(PhotoTargetType.PLACE, 7L, 10)).thenReturn(List.of("oldkey1", "oldkey2"));

        verifier.verify(5);

        verify(repository).markApproved(5, 0.9, NOW);
        verify(storage).delete("oldkey1");
        verify(storage).delete("oldkey2");
        verify(repository, never()).markRejected(anyLong(), any(), any(), any());
    }

    @Test
    void placeContextAndReferenceImageAreSentToTheAiService() {
        pendingPlacePhoto(5);
        when(ai.verify(any())).thenReturn(verdict(true, true, false, 0.9));

        verifier.verify(5);

        ArgumentCaptor<VerifyRequest> request = ArgumentCaptor.forClass(VerifyRequest.class);
        verify(ai).verify(request.capture());
        assertThat(request.getValue()).isEqualTo(new VerifyRequest("BAUG", "PLACE", "Galata Kulesi", "ATTRACTION",
                "İstanbul", "Beyoğlu", "https://upload.wikimedia.org/galata.jpg"));
    }

    @Test
    void districtPhotoIsCheckedWithoutCategoryOrReference() {
        when(repository.findById(6L)).thenReturn(Optional.of(new PhotoRow(6, 1, null, 3L, PhotoStatus.PENDING,
                null, KEY, 1600, 1200, null, NOW)));
        when(ai.verify(any())).thenReturn(verdict(true, true, false, 0.7));

        verifier.verify(6);

        ArgumentCaptor<VerifyRequest> request = ArgumentCaptor.forClass(VerifyRequest.class);
        verify(ai).verify(request.capture());
        assertThat(request.getValue().targetType()).isEqualTo("DISTRICT");
        assertThat(request.getValue().category()).isNull();
        assertThat(request.getValue().referenceImageUrl()).isNull();
        verify(repository).pruneApproved(PhotoTargetType.DISTRICT, 3L, 10);
    }

    @Test
    void verdictsMapToRejectReasons() {
        assertThat(PhotoVerifier.decide(verdict(true, true, false, 0.6))).isEmpty();
        assertThat(PhotoVerifier.decide(verdict(true, true, false, 0.59))).contains(RejectReason.NOT_RELEVANT);
        assertThat(PhotoVerifier.decide(verdict(false, true, false, 0.95))).contains(RejectReason.NOT_RELEVANT);
        assertThat(PhotoVerifier.decide(verdict(true, true, true, 0.95))).contains(RejectReason.PEOPLE);
        // Unsafe wins over everything else
        assertThat(PhotoVerifier.decide(verdict(true, false, true, 0.95))).contains(RejectReason.UNSAFE);
    }

    @Test
    void rejectedPhotoKeepsItsFilesForTheUsersList() {
        pendingPlacePhoto(5);
        when(ai.verify(any())).thenReturn(verdict(true, true, true, 0.9));

        verifier.verify(5);

        verify(repository).markRejected(5, RejectReason.PEOPLE, 0.9, NOW);
        verify(repository, never()).markApproved(anyLong(), anyDouble(), any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void aiErrorLeavesThePhotoPendingForTheRetry() {
        pendingPlacePhoto(5);
        when(ai.verify(any())).thenThrow(new IllegalStateException("502 Bad Gateway"));

        verifier.verify(5);

        verify(repository, never()).markApproved(anyLong(), anyDouble(), any());
        verify(repository, never()).markRejected(anyLong(), any(), any(), any());
    }

    @Test
    void withoutAiServiceNothingIsEverApproved() {
        pendingPlacePhoto(5);
        when(ai.isEnabled()).thenReturn(false);

        verifier.verify(5);

        verify(ai, never()).verify(any());
        verify(repository, never()).markApproved(anyLong(), anyDouble(), any());
    }

    @Test
    void alreadyReviewedPhotoIsNotCheckedAgain() {
        when(repository.findById(5L)).thenReturn(Optional.of(new PhotoRow(5, 1, 7L, null, PhotoStatus.APPROVED,
                null, KEY, 1600, 1200, 0.9, NOW)));

        verifier.verify(5);

        verify(ai, never()).verify(any());
    }
}
