-- Users rate food and drink places (1-5 stars, one rating per user per place). The sums live on the place too, so
-- lists and the route planner never count rows: user_rating_sum / user_rating_count, and favorite_count = how many
-- users saved the place ("Kaydedilenler"). PlaceCommunityService keeps them in step; a nightly job recounts.
CREATE TABLE place_ratings (
    place_id   BIGINT      NOT NULL REFERENCES places (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    stars      SMALLINT    NOT NULL CHECK (stars BETWEEN 1 AND 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (place_id, user_id)
);
CREATE INDEX idx_place_ratings_user ON place_ratings (user_id, updated_at);

ALTER TABLE places ADD COLUMN user_rating_sum INT NOT NULL DEFAULT 0;
ALTER TABLE places ADD COLUMN user_rating_count INT NOT NULL DEFAULT 0;
ALTER TABLE places ADD COLUMN favorite_count INT NOT NULL DEFAULT 0;

UPDATE places p SET favorite_count = s.saved
FROM (SELECT place_id, count(*) AS saved FROM saved_places GROUP BY place_id) s
WHERE s.place_id = p.id;

-- A district's favourites first (Explore): only places with any signal need the index
CREATE INDEX idx_places_community ON places (district_id)
    WHERE user_rating_count > 0 OR favorite_count > 0;
