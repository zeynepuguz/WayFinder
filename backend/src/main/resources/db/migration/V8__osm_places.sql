-- Places imported from OpenStreetMap (all of Istanbul). osm_id is "<type>/<id>", e.g. "node/123" or "way/456".
-- NULL for places that did not come from OSM (hand-verified / admin rows); NULLs never collide in a unique index.
ALTER TABLE places ADD COLUMN osm_id VARCHAR(40);

-- The importer upserts with ON CONFLICT (osm_id)
CREATE UNIQUE INDEX ux_places_osm_id ON places (osm_id);

-- "verified only" filters (source <> 'OSM') and the "any OSM rows yet?" check
CREATE INDEX idx_places_source ON places (source);
