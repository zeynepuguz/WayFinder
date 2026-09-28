-- Istanbul's 39 districts (ilçeler) and well-known neighbourhoods (semtler) from OpenStreetMap
-- (osm/OsmAreaImporter). Used to filter places by district and to start assistant routes where the user
-- says ("üsküdarda gezeceğiz") instead of at their GPS position.

-- admin_level=6 boundary relations; the polygon is assembled from the member ways in PostGIS
CREATE TABLE districts (
    id          BIGSERIAL PRIMARY KEY,
    -- "relation/1276889"
    osm_id      VARCHAR(40)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    -- ASCII, lowercase, "-" for spaces: "uskudar", "buyukcekmece"
    slug        VARCHAR(100) NOT NULL UNIQUE,
    -- NULL only if OSM's ways did not form a valid area (the importer logs a warning)
    geom        geometry(MultiPolygon, 4326),
    -- Where a route "in <district>" starts: the relation's label / admin_centre node, else a point inside the area
    label_lat   DOUBLE PRECISION NOT NULL,
    label_lon   DOUBLE PRECISION NOT NULL,
    -- Bounding box (map viewport)
    south       DOUBLE PRECISION,
    west        DOUBLE PRECISION,
    north       DOUBLE PRECISION,
    east        DOUBLE PRECISION,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- "which district contains this place" (ST_Intersects / ST_Contains)
CREATE INDEX idx_districts_geom ON districts USING GIST (geom);

-- place=suburb / quarter / neighbourhood nodes ("Moda", "Kuzguncuk", "Bebek")
CREATE TABLE areas (
    id           BIGSERIAL PRIMARY KEY,
    -- "node/123"
    osm_id       VARCHAR(40)  NOT NULL UNIQUE,
    name         VARCHAR(255) NOT NULL,
    slug         VARCHAR(255) NOT NULL,
    -- suburb / quarter / neighbourhood
    kind         VARCHAR(20)  NOT NULL,
    lat          DOUBLE PRECISION NOT NULL,
    lon          DOUBLE PRECISION NOT NULL,
    district_id  BIGINT REFERENCES districts (id) ON DELETE SET NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_areas_district ON areas (district_id);

-- Written by the importers (JDBC) from the district polygons, for verified and OSM places alike
ALTER TABLE places ADD COLUMN district_id BIGINT REFERENCES districts (id) ON DELETE SET NULL;

CREATE INDEX idx_places_district ON places (district_id);
