-- Why a user gave their stars: chosen from fixed answers (RatingReason: "Lezzetli", "Uygun fiyatlı", "Pahalı", ...).
-- They go with the rating: changing the stars drops answers that no longer fit, deleting the rating deletes them.
CREATE TABLE place_rating_reasons (
    place_id BIGINT      NOT NULL,
    user_id  BIGINT      NOT NULL,
    reason   VARCHAR(30) NOT NULL,
    PRIMARY KEY (place_id, user_id, reason),
    FOREIGN KEY (place_id, user_id) REFERENCES place_ratings (place_id, user_id) ON DELETE CASCADE
);
CREATE INDEX idx_place_rating_reasons_place ON place_rating_reasons (place_id, reason);

-- What enough users agree on, as place tags ("budget", "view", "family", ... and "pricey"): the route planner and
-- suggestions match interests against them like against the imported tags. Kept apart from places.tags, which the
-- OSM / Overture imports rewrite
ALTER TABLE places ADD COLUMN community_tags TEXT[] NOT NULL DEFAULT '{}';
