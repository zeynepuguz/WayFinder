-- Turkey's 81 provinces (iller) from OpenStreetMap (osm/OsmCityImporter). Every district, neighbourhood and
-- place belongs to one; the user picks a city instead of "all of Istanbul".

-- admin_level=4 boundary relations with ISO3166-2 "TR-.."; the polygon is assembled from the member ways in PostGIS
CREATE TABLE cities (
    id                 BIGSERIAL PRIMARY KEY,
    -- "relation/223474"
    osm_id             VARCHAR(40)  NOT NULL UNIQUE,
    name               VARCHAR(100) NOT NULL,
    -- ASCII, lowercase, "-" for spaces: "istanbul", "afyonkarahisar"
    slug               VARCHAR(100) NOT NULL UNIQUE,
    -- ISO3166-2 tag, e.g. "TR-34"; NULL when OSM has none
    iso_code           VARCHAR(10),
    -- OSM population tag (import order only); NULL when OSM has none
    population         BIGINT,
    -- NULL only if OSM's ways did not form a valid area (the importer logs a warning)
    geom               geometry(MultiPolygon, 4326),
    -- Where a route "in <city>" starts: the relation's admin_centre / label node, else a point inside the area
    label_lat          DOUBLE PRECISION NOT NULL,
    label_lon          DOUBLE PRECISION NOT NULL,
    -- Bounding box (map viewport)
    south              DOUBLE PRECISION,
    west               DOUBLE PRECISION,
    north              DOUBLE PRECISION,
    east               DOUBLE PRECISION,
    -- Last successful district + neighbourhood + place import of this city; NULL = never imported
    places_imported_at TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- "which city contains this point / place"
CREATE INDEX idx_cities_geom ON cities USING GIST (geom);

ALTER TABLE districts ADD COLUMN city_id BIGINT REFERENCES cities (id) ON DELETE SET NULL;
ALTER TABLE areas ADD COLUMN city_id BIGINT REFERENCES cities (id) ON DELETE SET NULL;
-- Written by the importers (JDBC) from the city polygons, for verified and OSM places alike
ALTER TABLE places ADD COLUMN city_id BIGINT REFERENCES cities (id) ON DELETE SET NULL;

CREATE INDEX idx_districts_city ON districts (city_id);
CREATE INDEX idx_places_city ON places (city_id);

-- District slugs repeat across cities ("merkez", "yenisehir"): unique per city only
ALTER TABLE districts DROP CONSTRAINT districts_slug_key;
CREATE UNIQUE INDEX ux_districts_city_slug ON districts (city_id, slug);

-- Neighbourhood names repeat even inside one city (several "Cumhuriyet" mahalleleri), so this one is not unique
CREATE INDEX idx_areas_city_slug ON areas (city_id, slug);

-- Backfill: everything imported so far is Istanbul (relation 223474). Its polygon is the union of its districts
-- until the province import replaces it (same osm_id) with the province boundary and its admin_centre.
INSERT INTO cities (osm_id, name, slug, iso_code, geom, label_lat, label_lon, south, west, north, east,
                    places_imported_at)
SELECT 'relation/223474', 'İstanbul', 'istanbul', 'TR-34', u.g,
       ST_Y(ST_PointOnSurface(u.g)), ST_X(ST_PointOnSurface(u.g)),
       ST_YMin(u.g), ST_XMin(u.g), ST_YMax(u.g), ST_XMax(u.g),
       (SELECT max(p.updated_at) FROM places p WHERE p.source = 'OSM')
FROM (SELECT ST_Multi(ST_CollectionExtract(ST_Union(geom), 3)) AS g
      FROM districts WHERE geom IS NOT NULL) u
WHERE u.g IS NOT NULL AND NOT ST_IsEmpty(u.g);

UPDATE districts SET city_id = (SELECT id FROM cities WHERE slug = 'istanbul');
UPDATE areas SET city_id = (SELECT id FROM cities WHERE slug = 'istanbul');
-- All places so far came from the Istanbul import or are hand-verified Istanbul (Kadıköy) places
UPDATE places SET city_id = (SELECT id FROM cities WHERE slug = 'istanbul');
