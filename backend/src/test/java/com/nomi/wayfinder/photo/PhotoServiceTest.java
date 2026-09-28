package com.nomi.wayfinder.photo;

import com.nomi.wayfinder.area.CityService;
import com.nomi.wayfinder.area.DistrictService;
import com.nomi.wayfinder.exception.BusinessException;
import com.nomi.wayfinder.exception.PlaceNotFoundException;
import com.nomi.wayfinder.exception.ResourceNotFoundException;
import com.nomi.wayfinder.photo.PhotoDtos.MyPhotoResponse;
import com.nomi.wayfinder.photo.PhotoDtos.PhotoResponse;
import com.nomi.wayfinder.photo.PhotoDtos.UploadResponse;
import com.nomi.wayfinder.photo.UserPhotoRepository.NewPhoto;
import com.nomi.wayfinder.photo.UserPhotoRepository.OwnPhoto;
import com.nomi.wayfinder.photo.UserPhotoRepository.PhotoRow;
import com.nomi.wayfinder.photo.UserPhotoRepository.PlaceTarget;
import com.nomi.wayfinder.photo.UserPhotoRepository.PublicPhoto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhotoServiceTest {

    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    private static final Instant NOW = Instant.parse("2026-09-28T09:00:00Z");
    // Galata Tower
    private static final double LAT = 41.0256;
    private static final double LON = 28.9742;
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private UserPhotoRepository repository;
    private PhotoStorage storage;
    private PhotoUploadLimiter limiter;
    private PhotoVerificationQueue queue;
    private CityService cityService;
    private DistrictService districtService;
    private PhotoService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserPhotoRepository.class);
        storage = mock(PhotoStorage.class);
        limiter = mock(PhotoUploadLimiter.class);
        queue = mock(PhotoVerificationQueue.class);
        cityService = mock(CityService.class);
        districtService = mock(DistrictService.class);
        when(repository.findPlaceTarget(7L)).thenReturn(Optional.of(new PlaceTarget(7, "Galata Kulesi", "ATTRACTION",
                LAT, LON, "İstanbul", "Beyoğlu", null)));
        when(repository.insert(any())).thenReturn(42L);
        when(storage.save(any(), any())).thenReturn(KEY);
        when(storage.url(anyString())).thenAnswer(inv -> "/media/photos/" + inv.getArgument(0) + ".jpg");
        when(storage.thumbUrl(anyString())).thenAnswer(inv -> "/media/photos/" + inv.getArgument(0) + "_t.jpg");
        service = new PhotoService(repository, new PhotoImageProcessor(TimeZone.getTimeZone(ISTANBUL)), storage,
                limiter, queue, cityService, districtService, Clock.fixed(NOW, ISTANBUL));
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private NewPhoto inserted() {
        ArgumentCaptor<NewPhoto> captor = ArgumentCaptor.forClass(NewPhoto.class);
        verify(repository).insert(captor.capture());
        return captor.getValue();
    }

    @Test
    void photoTakenAtThePlaceWaitsForTheAiCheck() {
        // ~100 m north of the tower, 20 m accuracy
        UploadResponse response = service.uploadForPlace(1, 7, TestImages.jpeg(800, 600), LAT + 0.0009, LON, 20.0);

        assertThat(response).isEqualTo(new UploadResponse(42, "PENDING", null, null));
        NewPhoto row = inserted();
        assertThat(row.status()).isEqualTo(PhotoStatus.PENDING);
        assertThat(row.proof()).isEqualTo(ProofSource.DEVICE);
        assertThat(row.distanceMeters()).isBetween(95.0, 105.0);
        assertThat(row.placeId()).isEqualTo(7L);
        assertThat(row.districtId()).isNull();
        assertThat(row.fileKey()).isEqualTo(KEY);
        verify(limiter).acquire(1, PhotoTargetType.PLACE, 7);
        verify(queue).submit(42);
    }

    @Test
    void exifGpsFarAwayIsRejectedEvenWhenTheUserStandsAtThePlace() {
        // Taken at Hagia Sophia (~2 km away), uploaded next to the tower
        byte[] photo = TestImages.jpegWithExif(800, 600, 41.0086, 28.9802, null, 1);

        UploadResponse response = service.uploadForPlace(1, 7, photo, LAT, LON, 5.0);

        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.rejectReason()).isEqualTo("NOT_NEAR");
        assertThat(response.rejectMessage()).isEqualTo("Fotoğraf bu mekanın yakınında çekilmemiş görünüyor.");
        assertThat(inserted().proof()).isEqualTo(ProofSource.EXIF);
        verify(queue, never()).submit(anyLong());
    }

    @Test
    void withoutAnyUsablePositionItCannotBeVerified() {
        UploadResponse noPosition = service.uploadForPlace(1, 7, TestImages.jpeg(800, 600), null, null, null);
        UploadResponse imprecise = service.uploadForPlace(1, 7, TestImages.jpeg(800, 600), LAT, LON, 500.0);

        assertThat(noPosition.rejectReason()).isEqualTo("NO_LOCATION");
        assertThat(imprecise.rejectReason()).isEqualTo("NO_LOCATION");
        assertThat(noPosition.rejectMessage()).startsWith("Konum bilgisi olmadığı için doğrulanamadı.");
        verify(queue, never()).submit(anyLong());
    }

    @Test
    void photoTakenYearsAgoIsStale() {
        byte[] photo = TestImages.jpegWithExif(800, 600, LAT, LON, "2021:05:01 10:00:00", 1);

        UploadResponse response = service.uploadForPlace(1, 7, photo, null, null, null);

        assertThat(response.rejectReason()).isEqualTo("NOT_RELEVANT");
        assertThat(response.rejectMessage()).contains("çok eski");
        assertThat(inserted().takenAt()).isNotNull();
    }

    @Test
    void districtPhotoIsCheckedAgainstThePolygonWithABorder() {
        when(repository.distanceToDistrict(eq(3L), anyDouble(), anyDouble())).thenReturn(150.0, 900.0);

        UploadResponse near = service.uploadForDistrict(1, 3, TestImages.jpeg(800, 600), 40.99, 29.03, 10.0);
        UploadResponse far = service.uploadForDistrict(1, 3, TestImages.jpeg(800, 600), 40.99, 29.03, 10.0);

        assertThat(near.status()).isEqualTo("PENDING");
        assertThat(far.rejectReason()).isEqualTo("NOT_NEAR");
        assertThat(far.rejectMessage()).isEqualTo("Fotoğraf bu bölgede çekilmemiş görünüyor.");
        verify(limiter, times(2)).acquire(1, PhotoTargetType.DISTRICT, 3);
    }

    @Test
    void quotaIsCheckedBeforeAnythingIsStored() {
        doThrow(new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "limit")).when(limiter)
                .acquire(1, PhotoTargetType.PLACE, 7);

        assertThatThrownBy(() -> service.uploadForPlace(1, 7, TestImages.jpeg(800, 600), LAT, LON, 5.0))
                .isInstanceOf(BusinessException.class);
        verify(storage, never()).save(any(), any());
        verify(repository, never()).insert(any());
    }

    @Test
    void unsupportedFileIsRefusedWithoutStoringIt() {
        assertThatThrownBy(() -> service.uploadForPlace(1, 7, "not an image at all".getBytes(), LAT, LON, 5.0))
                .isInstanceOf(PhotoRejectedException.class);
        verify(storage, never()).save(any(), any());
    }

    @Test
    void unknownPlaceIs404() {
        assertThatThrownBy(() -> service.uploadForPlace(1, 99, TestImages.jpeg(800, 600), LAT, LON, 5.0))
                .isInstanceOf(PlaceNotFoundException.class);
        assertThatThrownBy(() -> service.placePhotos(99)).isInstanceOf(PlaceNotFoundException.class);
    }

    @Test
    void unknownCityOrDistrictIs404() {
        when(cityService.findBySlug("istanbul")).thenReturn(Optional.of(new CityService.City(1, "istanbul",
                "İstanbul", 41, 29)));
        when(districtService.findIdBySlug(1, "kadikoy")).thenReturn(Optional.of(3L));

        assertThat(service.requireDistrictId("istanbul", "kadikoy")).isEqualTo(3L);
        assertThatThrownBy(() -> service.requireDistrictId("atlantis", "kadikoy"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.requireDistrictId("istanbul", "nowhere"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void collageShowsOnlyTheUploadersFirstName() {
        when(repository.approvedOfPlace(7, 10)).thenReturn(List.of(
                new PublicPhoto(1, KEY, 1600, 1200, NOW, "Zeynep Uğuz", 7L, null),
                new PublicPhoto(2, KEY, 1200, 1600, NOW, "   ", 7L, null)));

        List<PhotoResponse> photos = service.placePhotos(7);

        assertThat(photos).extracting(PhotoResponse::uploader).containsExactly("Zeynep", "Nomi kullanıcısı");
        assertThat(photos.getFirst().url()).isEqualTo("/media/photos/" + KEY + ".jpg");
        assertThat(photos.getFirst().thumbUrl()).isEqualTo("/media/photos/" + KEY + "_t.jpg");
        // Place collages do not repeat the place
        assertThat(photos.getFirst().placeName()).isNull();
        assertThat(photos.getFirst().placeId()).isNull();
    }

    @Test
    void myPhotosExplainRejectionsInTheUsersLanguage() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        when(repository.ofUser(1, 50)).thenReturn(List.of(
                new OwnPhoto(3, KEY, PhotoStatus.REJECTED, RejectReason.PEOPLE, 0.9, NOW, 7L, "Galata Kulesi",
                        "istanbul", "İstanbul", "beyoglu", "Beyoğlu", false),
                new OwnPhoto(2, null, PhotoStatus.REJECTED, RejectReason.NOT_RELEVANT, null, NOW, null, null,
                        "istanbul", "İstanbul", "kadikoy", "Kadıköy", true),
                new OwnPhoto(1, KEY, PhotoStatus.APPROVED, null, 0.8, NOW, 7L, "Galata Kulesi",
                        "istanbul", "İstanbul", "beyoglu", "Beyoğlu", false)));

        List<MyPhotoResponse> photos = service.myPhotos(1);

        assertThat(photos.get(0).rejectMessage()).isEqualTo("We don't publish photos where people are in the foreground.");
        assertThat(photos.get(0).target()).isEqualTo(new PhotoDtos.Target("PLACE", 7L, "Galata Kulesi", "istanbul",
                "İstanbul", "beyoglu", "Beyoğlu"));
        // Files of old rejected photos are gone; NOT_RELEVANT without an AI verdict = taken years ago
        assertThat(photos.get(1).url()).isNull();
        assertThat(photos.get(1).rejectMessage()).isEqualTo("The photo seems too old; we only publish recent photos.");
        assertThat(photos.get(1).target().type()).isEqualTo("DISTRICT");
        assertThat(photos.get(1).target().name()).isEqualTo("Kadıköy");
        assertThat(photos.get(2).status()).isEqualTo("APPROVED");
        assertThat(photos.get(2).rejectMessage()).isNull();
    }

    @Test
    void onlyTheOwnerOrAnAdminCanDeleteAPhoto() {
        when(repository.findById(9L)).thenReturn(Optional.of(new PhotoRow(9, 1, 7L, null, PhotoStatus.APPROVED,
                null, KEY, 1600, 1200, 0.9, NOW)));

        assertThatThrownBy(() -> service.delete(2, false, 9)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(anyLong());

        service.delete(1, false, 9);
        service.delete(2, true, 9);

        verify(repository, times(2)).delete(9);
        verify(storage, times(2)).delete(KEY);
    }

    @Test
    void uploaderNameIsTheFirstWordOnly() {
        assertThat(PhotoService.uploaderName("Ayşe Nur Demir")).isEqualTo("Ayşe");
        assertThat(PhotoService.uploaderName(null)).isEqualTo("Nomi kullanıcısı");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(PhotoService.uploaderName("")).isEqualTo("Nomi user");
    }
}
