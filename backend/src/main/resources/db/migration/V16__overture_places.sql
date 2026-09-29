-- Second place source: Overture Maps (Foursquare, Meta, Microsoft places; overture/OverturePlaceImporter).
-- Only food places (breakfast, restaurants, cafés, desserts) come from it; sights stay OpenStreetMap + Wikidata.

-- The Overture place this row is (source = 'OVERTURE'), or that confirmed an OSM row (same name, close by)
ALTER TABLE places ADD COLUMN overture_id VARCHAR(64);
ALTER TABLE places ADD CONSTRAINT uq_places_overture_id UNIQUE (overture_id);

-- Contact data from Overture (the business' own phone / website)
ALTER TABLE places
    ADD COLUMN phone   VARCHAR(50),
    ADD COLUMN website VARCHAR(500);

-- Overture's 0..1 certainty that the place exists
ALTER TABLE places ADD COLUMN overture_confidence DOUBLE PRECISION;

-- An OSM food place that no current Overture source knows (likely closed, e.g. added to OSM in 2017 and never
-- touched again): left out of routes, recommendations and lists; still on its own page (saved places, old routes)
ALTER TABLE places ADD COLUMN unconfirmed BOOLEAN NOT NULL DEFAULT FALSE;

-- The Overture release a city was last imported from (NULL = never)
ALTER TABLE cities
    ADD COLUMN overture_imported_at TIMESTAMPTZ,
    ADD COLUMN overture_release     VARCHAR(20);
