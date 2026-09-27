-- One free-licensed photo per place from Wikimedia Commons (never Google Images: copyright).
-- wikidata / commons_file come from OSM tags (wikidata, wikimedia_commons, image); WikimediaImageResolver
-- turns them into an image with its author, license and Commons description page (needed for attribution).
ALTER TABLE places
    -- Wikidata item id, e.g. "Q12506"; its P18 (image) claim names a Commons file
    ADD COLUMN wikidata          VARCHAR(20),
    -- Commons file title without the "File:" prefix, e.g. "Hagia Sophia Mars 2013.jpg"
    ADD COLUMN commons_file      VARCHAR(255),
    -- 800 px wide thumbnail on upload.wikimedia.org
    ADD COLUMN image_url         VARCHAR(1000),
    ADD COLUMN image_author      VARCHAR(300),
    -- e.g. "CC BY-SA 4.0", "CC0", "Public domain"
    ADD COLUMN image_license     VARCHAR(100),
    -- The Commons file description page (attribution link)
    ADD COLUMN image_source_url  VARCHAR(1000),
    -- Last time the resolver looked (also when nothing usable was found); NULL = not checked yet
    ADD COLUMN image_checked_at  TIMESTAMPTZ;

-- The resolver's "what still needs a look" query
CREATE INDEX idx_places_image_pending ON places (image_checked_at)
    WHERE wikidata IS NOT NULL OR commons_file IS NOT NULL;
