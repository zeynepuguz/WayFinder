package com.nomi.wayfinder.photo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

public final class PhotoDtos {

    private PhotoDtos() {
    }

    /**
     * Answer of an upload (202). status is PENDING (AI check running in the background) or already REJECTED
     * when the location proof failed (then rejectReason / rejectMessage say why).
     */
    public record UploadResponse(long id, String status, String rejectReason, String rejectMessage) {
    }

    /**
     * A published photo in a collage. placeId / placeName only in district collages, for photos of a place
     * inside the district.
     */
    public record PhotoResponse(
            long id,
            String url,
            String thumbUrl,
            int width,
            int height,
            Instant createdAt,
            // First name only ("Zeynep"), or "Nomi kullanıcısı" / "Nomi user"
            String uploader,
            @JsonInclude(JsonInclude.Include.NON_NULL) Long placeId,
            @JsonInclude(JsonInclude.Include.NON_NULL) String placeName
    ) {
    }

    /** One of the user's own uploads, whatever its status. url / thumbUrl are null once a rejected photo's files are gone. */
    public record MyPhotoResponse(
            long id,
            String url,
            String thumbUrl,
            String status,
            String rejectReason,
            String rejectMessage,
            Target target,
            Instant createdAt
    ) {
    }

    /**
     * @param type     PLACE or DISTRICT
     * @param id       the place id (PLACE only)
     * @param name     place or district name
     * @param city     city slug (for /cities/{city}/districts/{district}/photos and links)
     * @param district district slug (a place's district, when known)
     */
    public record Target(String type, Long id, String name, String city, String cityName, String district,
                         String districtName) {
    }
}
