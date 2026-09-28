-- 1) Places that are not really places a visitor can go to (a school canteen, a staff dining hall, a police club)
--    but that a route stop, a saved place or a user photo still points at are hidden instead of deleted
--    (osm/PlaceRealismFilter, osm/PlaceRealismCleanup). Hidden places are left out of every list, map, search,
--    recommendation, plan and count; only their own detail page (links from old routes) still opens.
ALTER TABLE places ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;

-- 2) Real-world interest in a place, from open Wikimedia signals (popularity/PlacePopularityService):
--    how many Wikipedia language editions have an article about it (Wikidata sitelinks) and how often its Turkish
--    and English Wikipedia articles were read in the last two full months (Wikimedia pageviews API).
--    NULL = not known (no wikidata id, or not checked yet); nothing is estimated.
ALTER TABLE places
    ADD COLUMN wiki_sitelinks        INT,
    -- Turkish + English Wikipedia article views (users, all access) in the last two full calendar months
    ADD COLUMN wiki_pageviews        INT,
    -- 2 * ln(1 + wiki_sitelinks) + ln(1 + wiki_pageviews); see PlacePopularity.score
    ADD COLUMN popularity            DOUBLE PRECISION,
    -- Last time the lookup ran for the place (also when Wikidata had nothing); NULL = never
    ADD COLUMN popularity_checked_at TIMESTAMPTZ;

-- Popular routes: the most popular visible places of a city
CREATE INDEX idx_places_city_popularity ON places (city_id, popularity DESC)
    WHERE popularity IS NOT NULL AND NOT hidden;
-- The popularity job's "what still needs a look" query
CREATE INDEX idx_places_popularity_pending ON places (popularity_checked_at) WHERE wikidata IS NOT NULL;
