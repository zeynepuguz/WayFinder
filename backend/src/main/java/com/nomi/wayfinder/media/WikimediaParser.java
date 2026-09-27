package com.nomi.wayfinder.media;

import com.nomi.wayfinder.media.WikimediaResponses.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the Wikidata and Commons API answers (pure logic, unit tested with sample JSON; no network).
 * Only photos we are allowed to show are accepted: jpg / jpeg / png / webp files under CC0, public domain,
 * CC BY or CC BY-SA. Everything else (no license, "Fair use", GFDL-only, NC / ND, svg / tif / pdf, ...) is skipped.
 */
final class WikimediaParser {

    static final int MAX_AUTHOR = 300;
    static final int MAX_LICENSE = 100;
    static final int MAX_URL = 1000;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");
    private static final Map<String, String> NAMED_ENTITIES = Map.of(
            "&amp;", "&", "&lt;", "<", "&gt;", ">", "&quot;", "\"", "&apos;", "'", "&nbsp;", " ");

    private WikimediaParser() {
    }

    // The API reported a problem (e.g. "maxlag"); the request should be retried later
    static final class ApiErrorException extends RuntimeException {

        private final String code;
        private final String id;

        ApiErrorException(ApiError error) {
            super("Wikimedia API error " + error.code() + ": " + error.info());
            this.code = error.code();
            this.id = error.id();
        }

        String code() {
            return code;
        }

        // The unknown Wikidata id on "no-such-entity", else null
        String id() {
            return id;
        }
    }

    /**
     * Wikidata id -> its image (P18) file name. Preferred-rank claims win over normal ones, deprecated ones
     * are ignored. Items without P18 and missing items are left out (file formats are checked later).
     * A requested id that was redirected (merged) is returned under the requested id.
     */
    static Map<String, String> imageFiles(String json, JsonMapper jsonMapper) {
        WikidataResponse response = jsonMapper.readValue(json, WikidataResponse.class);
        if (response.error() != null) {
            throw new ApiErrorException(response.error());
        }
        Map<String, String> files = new LinkedHashMap<>();
        if (response.entities() == null) {
            return files;
        }
        for (Map.Entry<String, Entity> entry : response.entities().entrySet()) {
            Entity entity = entry.getValue();
            if (entity == null || entity.missing() != null || entity.claims() == null) {
                continue;
            }
            String file = p18(entity.claims().get("P18"));
            if (file == null) {
                continue;
            }
            files.put(entry.getKey(), file);
            if (entity.redirects() != null && entity.redirects().from() != null) {
                files.put(entity.redirects().from(), file);
            }
            if (entity.id() != null) {
                files.put(entity.id(), file);
            }
        }
        return files;
    }

    private static String p18(List<Claim> claims) {
        if (claims == null) {
            return null;
        }
        String normal = null;
        for (Claim claim : claims) {
            if (claim == null || "deprecated".equals(claim.rank()) || claim.mainsnak() == null
                    || !"value".equals(claim.mainsnak().snaktype()) || claim.mainsnak().datavalue() == null
                    || !(claim.mainsnak().datavalue().value() instanceof String value) || value.isBlank()) {
                continue;
            }
            String file = value.trim();
            if ("preferred".equals(claim.rank())) {
                return file;
            }
            if (normal == null) {
                normal = file;
            }
        }
        return normal;
    }

    /**
     * Commons file name (as requested, without "File:") -> the usable image. Files that are missing, not a
     * photo format or not under a free license are left out. Title normalization and redirects the API
     * reports are followed back to the requested name.
     */
    static Map<String, CommonsImage> images(String json, Collection<String> requestedFiles, JsonMapper jsonMapper) {
        CommonsResponse response = jsonMapper.readValue(json, CommonsResponse.class);
        if (response.error() != null) {
            throw new ApiErrorException(response.error());
        }
        Map<String, CommonsImage> images = new LinkedHashMap<>();
        Query query = response.query();
        if (query == null || query.pages() == null) {
            return images;
        }

        Map<String, String> renamed = new HashMap<>();
        for (List<Redirect> list : Arrays.asList(query.normalized(), query.redirects())) {
            if (list != null) {
                list.stream().filter(r -> r.from() != null && r.to() != null).forEach(r -> renamed.put(r.from(), r.to()));
            }
        }
        Map<String, Page> pages = new HashMap<>();
        query.pages().stream().filter(p -> p != null && p.title() != null).forEach(p -> pages.put(p.title(), p));

        for (String file : requestedFiles) {
            String title = "File:" + file;
            // normalized, then redirected (at most a few hops; guard against loops)
            for (int hop = 0; hop < 3 && renamed.containsKey(title); hop++) {
                title = renamed.get(title);
            }
            CommonsImage image = toImage(pages.get(title));
            if (image != null) {
                images.put(file, image);
            }
        }
        return images;
    }

    private static CommonsImage toImage(Page page) {
        if (page == null || Boolean.TRUE.equals(page.missing()) || Boolean.TRUE.equals(page.invalid())
                || page.imageinfo() == null || page.imageinfo().isEmpty() || !isImageFile(page.title())) {
            return null;
        }
        ImageInfo info = page.imageinfo().getFirst();
        Map<String, MetadataValue> meta = info.extmetadata() == null ? Map.of() : info.extmetadata();

        String license = text(meta.get("LicenseShortName"));
        if ("true".equalsIgnoreCase(text(meta.get("NonFree"))) || !isFreeLicense(license)) {
            return null;
        }
        String url = withoutTracking(info.thumburl() != null ? info.thumburl() : info.url());
        String sourceUrl = withoutTracking(info.descriptionurl());
        if (url == null || url.length() > MAX_URL || (sourceUrl != null && sourceUrl.length() > MAX_URL)) {
            return null;
        }
        return new CommonsImage(url, cleanArtist(text(meta.get("Artist"))),
                truncate(license.trim(), MAX_LICENSE), sourceUrl);
    }

    // The API adds "?utm_source=...&utm_campaign=..." to file URLs; the image is the same without them
    static String withoutTracking(String url) {
        if (url == null) {
            return null;
        }
        int question = url.indexOf('?');
        if (question < 0) {
            return url;
        }
        String kept = Arrays.stream(url.substring(question + 1).split("&"))
                .filter(p -> !p.isEmpty() && !p.startsWith("utm_"))
                .collect(java.util.stream.Collectors.joining("&"));
        return kept.isEmpty() ? url.substring(0, question) : url.substring(0, question + 1) + kept;
    }

    /**
     * CC0, public domain, CC BY and CC BY-SA (any version / port). Everything else is rejected, including
     * a missing license, "Fair use", GFDL-only, non-commercial (NC) and no-derivatives (ND) licenses.
     */
    static boolean isFreeLicense(String licenseShortName) {
        if (licenseShortName == null || licenseShortName.isBlank()) {
            return false;
        }
        // "CC-BY-SA-4.0", "cc by-sa 4.0" -> "CC BY SA 4.0"
        String name = licenseShortName.toUpperCase(Locale.ROOT).replaceAll("[-_\\s]+", " ").trim();
        if (name.startsWith("CC0") || name.startsWith("CC ZERO") || name.startsWith("PUBLIC DOMAIN")) {
            return true;
        }
        if (!name.startsWith("CC BY")) {
            return false;
        }
        List<String> words = Arrays.asList(name.split(" "));
        return !words.contains("NC") && !words.contains("ND");
    }

    // jpg / jpeg / png / webp only (no svg, tif, pdf, gif, video ...)
    static boolean isImageFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && IMAGE_EXTENSIONS.contains(fileName.substring(dot + 1).trim().toLowerCase(Locale.ROOT));
    }

    // Commons' Artist is HTML ("<a href=...>Name</a>"); plain text, max MAX_AUTHOR characters, null if empty
    static String cleanArtist(String html) {
        if (html == null) {
            return null;
        }
        String text = TAG.matcher(html).replaceAll(" ");
        for (Map.Entry<String, String> entity : NAMED_ENTITIES.entrySet()) {
            text = text.replace(entity.getKey(), entity.getValue());
        }
        Matcher m = NUMERIC_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String decoded;
            try {
                int codePoint = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                decoded = Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : "";
            } catch (NumberFormatException e) {
                decoded = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(decoded));
        }
        m.appendTail(sb);
        String clean = sb.toString().replaceAll("\\s+", " ").trim();
        return clean.isEmpty() ? null : truncate(clean, MAX_AUTHOR);
    }

    private static String text(MetadataValue value) {
        return value != null && value.value() instanceof String s ? s : null;
    }

    private static String truncate(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        // Do not cut a surrogate pair in half
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end).trim();
    }

    /**
     * @param url       thumbnail (or original when smaller) on upload.wikimedia.org
     * @param author    plain text or null
     * @param license   LicenseShortName, e.g. "CC BY-SA 4.0"
     * @param sourceUrl Commons file description page
     */
    record CommonsImage(String url, String author, String license, String sourceUrl) {
    }
}
