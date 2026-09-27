package com.nomi.wayfinder.media;

import com.nomi.wayfinder.media.WikimediaParser.CommonsImage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Hand-written answers in the shape of the Wikidata / Commons APIs; no network
class WikimediaParserTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private static final String WIKIDATA = """
            {
              "entities": {
                "Q1": {"type": "item", "id": "Q1", "claims": {
                  "P18": [
                    {"rank": "normal", "mainsnak": {"snaktype": "value", "property": "P18",
                      "datavalue": {"value": "Normal Photo.jpg", "type": "string"}}},
                    {"rank": "preferred", "mainsnak": {"snaktype": "value", "property": "P18",
                      "datavalue": {"value": "Preferred Photo.jpg", "type": "string"}}}
                  ],
                  "P31": [{"rank": "normal", "mainsnak": {"snaktype": "value",
                      "datavalue": {"value": {"entity-type": "item", "id": "Q33506"}, "type": "wikibase-entityid"}}}]
                }},
                "Q2": {"type": "item", "id": "Q2", "claims": {
                  "P18": [
                    {"rank": "deprecated", "mainsnak": {"snaktype": "value",
                      "datavalue": {"value": "Old.jpg", "type": "string"}}},
                    {"rank": "normal", "mainsnak": {"snaktype": "somevalue"}}
                  ]
                }},
                "Q3": {"type": "item", "id": "Q3", "claims": {}},
                "Q4": {"id": "Q4", "missing": ""},
                "Q50": {"type": "item", "id": "Q50", "redirects": {"from": "Q5", "to": "Q50"}, "claims": {
                  "P18": [{"rank": "normal", "mainsnak": {"snaktype": "value",
                      "datavalue": {"value": "Merged.png", "type": "string"}}}]
                }}
              },
              "success": 1
            }
            """;

    @Test
    void readsP18PreferringPreferredRankAndSkippingDeprecatedMissingAndEmpty() {
        Map<String, String> files = WikimediaParser.imageFiles(WIKIDATA, jsonMapper);

        assertThat(files.get("Q1")).isEqualTo("Preferred Photo.jpg");
        assertThat(files).doesNotContainKeys("Q2", "Q3", "Q4");
        // A merged item is found under the id we asked for
        assertThat(files.get("Q5")).isEqualTo("Merged.png");
    }

    @Test
    void apiErrorsSuchAsMaxlagAreFailures() {
        String busy = """
                {"error": {"code": "maxlag", "info": "Waiting for a database server: 7 seconds lagged."}}""";

        assertThatThrownBy(() -> WikimediaParser.imageFiles(busy, jsonMapper))
                .isInstanceOf(WikimediaParser.ApiErrorException.class)
                .hasMessageContaining("maxlag");
        assertThatThrownBy(() -> WikimediaParser.images(busy, List.of("A.jpg"), jsonMapper))
                .isInstanceOf(WikimediaParser.ApiErrorException.class);

        // One unknown id fails the whole Wikidata request; the error names it so it can be dropped
        String unknown = """
                {"error": {"code": "no-such-entity", "info": "Could not find an entity with the ID \\"Q99\\".", "id": "Q99"}}""";
        assertThatThrownBy(() -> WikimediaParser.imageFiles(unknown, jsonMapper))
                .isInstanceOfSatisfying(WikimediaParser.ApiErrorException.class, e -> {
                    assertThat(e.code()).isEqualTo("no-such-entity");
                    assertThat(e.id()).isEqualTo("Q99");
                });
    }

    @Test
    void trackingParametersAreRemoved() {
        assertThat(WikimediaParser.withoutTracking("https://a.org/x.jpg?utm_source=s&utm_campaign=c"))
                .isEqualTo("https://a.org/x.jpg");
        assertThat(WikimediaParser.withoutTracking("https://a.org/x.jpg?utm_source=s&v=2")).isEqualTo("https://a.org/x.jpg?v=2");
        assertThat(WikimediaParser.withoutTracking("https://a.org/x.jpg")).isEqualTo("https://a.org/x.jpg");
        assertThat(WikimediaParser.withoutTracking(null)).isNull();
    }

    private static final String COMMONS = """
            {
              "batchcomplete": true,
              "query": {
                "normalized": [{"fromencoded": false, "from": "File:Galata_Tower.jpg", "to": "File:Galata Tower.jpg"}],
                "redirects": [{"from": "File:Old Name.jpg", "to": "File:New Name.jpg"}],
                "pages": [
                  {"pageid": 1, "ns": 6, "title": "File:Galata Tower.jpg", "imagerepository": "local",
                   "imageinfo": [{
                     "thumburl": "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Galata_Tower.jpg/960px-Galata_Tower.jpg?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail",
                     "thumbwidth": 800,
                     "url": "https://upload.wikimedia.org/wikipedia/commons/a/ab/Galata_Tower.jpg?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=original",
                     "descriptionurl": "https://commons.wikimedia.org/wiki/File:Galata_Tower.jpg",
                     "extmetadata": {
                       "Artist": {"value": "<a href=\\"//commons.wikimedia.org/wiki/User:Ay\\" title=\\"User:Ay\\">Ayşe &amp; Mehmet</a>\\n<span>(photo)</span>", "source": "commons-desc-page"},
                       "LicenseShortName": {"value": "CC BY-SA 4.0", "source": "commons-desc-page", "hidden": ""}
                     }}]},
                  {"pageid": 2, "ns": 6, "title": "File:New Name.jpg",
                   "imageinfo": [{
                     "url": "https://upload.wikimedia.org/wikipedia/commons/1/12/New_Name.jpg",
                     "descriptionurl": "https://commons.wikimedia.org/wiki/File:New_Name.jpg",
                     "extmetadata": {"LicenseShortName": {"value": "Public domain"}}}]},
                  {"pageid": 3, "ns": 6, "title": "File:Fair.jpg",
                   "imageinfo": [{"thumburl": "https://upload.wikimedia.org/x/800px-Fair.jpg",
                     "descriptionurl": "https://commons.wikimedia.org/wiki/File:Fair.jpg",
                     "extmetadata": {"LicenseShortName": {"value": "Fair use"}}}]},
                  {"pageid": 4, "ns": 6, "title": "File:NoLicense.jpg",
                   "imageinfo": [{"thumburl": "https://upload.wikimedia.org/x/800px-NoLicense.jpg",
                     "descriptionurl": "https://commons.wikimedia.org/wiki/File:NoLicense.jpg",
                     "extmetadata": {"Artist": {"value": "Someone"}}}]},
                  {"pageid": 5, "ns": 6, "title": "File:Logo.svg",
                   "imageinfo": [{"thumburl": "https://upload.wikimedia.org/x/800px-Logo.svg.png",
                     "descriptionurl": "https://commons.wikimedia.org/wiki/File:Logo.svg",
                     "extmetadata": {"LicenseShortName": {"value": "CC0"}}}]},
                  {"pageid": 6, "ns": 6, "title": "File:NonCommercial.jpg",
                   "imageinfo": [{"thumburl": "https://upload.wikimedia.org/x/800px-NonCommercial.jpg",
                     "descriptionurl": "https://commons.wikimedia.org/wiki/File:NonCommercial.jpg",
                     "extmetadata": {"LicenseShortName": {"value": "CC BY-NC-SA 2.0"}}}]},
                  {"ns": 6, "title": "File:Gone.jpg", "missing": true}
                ]
              }
            }
            """;

    @Test
    void acceptsOnlyFreeLicensedPhotosAndFollowsNormalizationAndRedirects() {
        Map<String, CommonsImage> images = WikimediaParser.images(COMMONS,
                List.of("Galata_Tower.jpg", "Old Name.jpg", "Fair.jpg", "NoLicense.jpg", "Logo.svg",
                        "NonCommercial.jpg", "Gone.jpg", "Never Returned.jpg"),
                jsonMapper);

        assertThat(images).containsOnlyKeys("Galata_Tower.jpg", "Old Name.jpg");

        CommonsImage galata = images.get("Galata_Tower.jpg");
        // Thumbnail, without the API's utm_* tracking parameters
        assertThat(galata.url()).isEqualTo(
                "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Galata_Tower.jpg/960px-Galata_Tower.jpg");
        assertThat(galata.author()).isEqualTo("Ayşe & Mehmet (photo)");
        assertThat(galata.license()).isEqualTo("CC BY-SA 4.0");
        assertThat(galata.sourceUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:Galata_Tower.jpg");

        // No thumbnail (smaller than 800 px) -> the original; no Artist -> no author
        CommonsImage renamed = images.get("Old Name.jpg");
        assertThat(renamed.url()).isEqualTo("https://upload.wikimedia.org/wikipedia/commons/1/12/New_Name.jpg");
        assertThat(renamed.author()).isNull();
        assertThat(renamed.license()).isEqualTo("Public domain");
    }

    @Test
    void licenseFilter() {
        assertThat(List.of("CC0", "CC0 1.0", "Public domain", "Public Domain Mark", "CC BY 2.0", "CC BY-SA 4.0",
                "CC-BY-SA-3.0", "CC BY-SA 3.0 de", "cc by 4.0"))
                .allMatch(WikimediaParser::isFreeLicense);
        assertThat(java.util.Arrays.asList(null, "", " ", "Fair use", "GFDL", "Attribution", "CC BY-NC 2.0",
                "CC BY-ND 4.0", "CC BY-NC-SA 3.0", "All rights reserved", "Copyrighted free use"))
                .noneMatch(WikimediaParser::isFreeLicense);
    }

    @Test
    void onlyPhotoFormats() {
        assertThat(List.of("A.jpg", "B.JPEG", "c.png", "d d.webp", "Ayasofya.JPG"))
                .allMatch(WikimediaParser::isImageFile);
        assertThat(java.util.Arrays.asList(null, "Logo.svg", "Scan.tif", "Scan.tiff", "Doc.pdf", "Anim.gif",
                "Video.webm", "noextension", ".jpg"))
                .noneMatch(WikimediaParser::isImageFile);
    }

    @Test
    void artistHtmlBecomesShortPlainText() {
        assertThat(WikimediaParser.cleanArtist("<bdi><a href=\"x\">Ali&nbsp;Veli</a></bdi>")).isEqualTo("Ali Veli");
        assertThat(WikimediaParser.cleanArtist("Ç&#305;nar &#x26; O&#39;Brien")).isEqualTo("Çınar & O'Brien");
        assertThat(WikimediaParser.cleanArtist("  <span> </span> ")).isNull();
        assertThat(WikimediaParser.cleanArtist(null)).isNull();
        assertThat(WikimediaParser.cleanArtist("a".repeat(400))).hasSize(WikimediaParser.MAX_AUTHOR);
    }
}
