package com.nomi.wayfinder.photo;

import com.nomi.wayfinder.i18n.Texts;

/**
 * Why a photo is not published. TOO_SMALL and UNSUPPORTED are answered right at the upload (400, nothing is
 * stored); the others end up on the photo's row and in the user's "my photos" list.
 */
public enum RejectReason {
    TOO_SMALL,
    NOT_NEAR,
    NO_LOCATION,
    NOT_RELEVANT,
    UNSAFE,
    PEOPLE,
    UNSUPPORTED;

    /**
     * @param stale NOT_RELEVANT because the photo was taken years ago (decided before any AI check)
     */
    public String message(PhotoTargetType target, boolean stale) {
        boolean place = target != PhotoTargetType.DISTRICT;
        return switch (this) {
            case TOO_SMALL -> Texts.t(
                    "Fotoğraf çok küçük; kısa kenarı en az " + PhotoImageProcessor.MIN_SHORT_SIDE + " piksel olmalı.",
                    "The photo is too small; its shorter side must be at least "
                            + PhotoImageProcessor.MIN_SHORT_SIDE + " pixels.");
            case NOT_NEAR -> place
                    ? Texts.t("Fotoğraf bu mekanın yakınında çekilmemiş görünüyor.",
                    "The photo doesn't seem to be taken near this place.")
                    : Texts.t("Fotoğraf bu bölgede çekilmemiş görünüyor.",
                    "The photo doesn't seem to be taken in this area.");
            case NO_LOCATION -> place
                    ? Texts.t("Konum bilgisi olmadığı için doğrulanamadı. Fotoğrafı mekandayken çekip yükle ya da "
                            + "konum izni ver.",
                    "It couldn't be verified without location data. Take the photo at the place and upload it "
                            + "there, or allow location access.")
                    : Texts.t("Konum bilgisi olmadığı için doğrulanamadı. Fotoğrafı bölgedeyken çekip yükle ya da "
                            + "konum izni ver.",
                    "It couldn't be verified without location data. Take the photo in the area and upload it "
                            + "there, or allow location access.");
            case NOT_RELEVANT -> stale
                    ? Texts.t("Fotoğraf çok eski görünüyor; yalnızca güncel fotoğrafları yayınlıyoruz.",
                    "The photo seems too old; we only publish recent photos.")
                    : place
                    ? Texts.t("Fotoğraf bu mekanı göstermiyor gibi görünüyor.",
                    "The photo doesn't seem to show this place.")
                    : Texts.t("Fotoğraf bu bölgeyi göstermiyor gibi görünüyor.",
                    "The photo doesn't seem to show this area.");
            case UNSAFE -> Texts.t("Bu fotoğraf yayın kurallarımıza uymuyor.",
                    "This photo doesn't meet our publishing rules.");
            case PEOPLE -> Texts.t("İnsanların ön planda olduğu fotoğrafları yayınlamıyoruz.",
                    "We don't publish photos where people are in the foreground.");
            case UNSUPPORTED -> Texts.t("Bu dosya türü desteklenmiyor; JPEG olarak kaydedip tekrar dene.",
                    "This file type isn't supported; save it as JPEG and try again.");
        };
    }
}
