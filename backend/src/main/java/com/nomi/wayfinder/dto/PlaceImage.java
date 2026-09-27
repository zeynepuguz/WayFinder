package com.nomi.wayfinder.dto;

import com.nomi.wayfinder.entity.Place;

/**
 * A place's photo from Wikimedia Commons (free license). The app must show author and license with a
 * link to sourceUrl (the Commons file page): CC BY / CC BY-SA require that attribution.
 *
 * @param url       ~800 px wide image on upload.wikimedia.org
 * @param author    plain text, may be null
 * @param license   short name, e.g. "CC BY-SA 4.0"
 * @param sourceUrl the Commons file description page
 */
public record PlaceImage(String url, String author, String license, String sourceUrl) {

    // null when the place has no image
    public static PlaceImage of(Place place) {
        if (place.getImageUrl() == null) {
            return null;
        }
        return new PlaceImage(place.getImageUrl(), place.getImageAuthor(), place.getImageLicense(),
                place.getImageSourceUrl());
    }
}
