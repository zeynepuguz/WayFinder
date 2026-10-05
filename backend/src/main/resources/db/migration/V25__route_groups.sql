-- Group plans: the owner shares a route with an invite code; members see it, vote on its places and (with a pass)
-- change it like the owner. Turning the code off stops new members; the ones in stay.
ALTER TABLE routes ADD COLUMN share_token VARCHAR(32) UNIQUE;

CREATE TABLE route_members (
    route_id  BIGINT      NOT NULL REFERENCES routes (id) ON DELETE CASCADE,
    user_id   BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (route_id, user_id)
);
CREATE INDEX idx_route_members_user ON route_members (user_id);

-- By place, not by stop: a replan makes new stops, the group's opinion of a place stays
CREATE TABLE route_votes (
    route_id BIGINT   NOT NULL REFERENCES routes (id) ON DELETE CASCADE,
    place_id BIGINT   NOT NULL,
    user_id  BIGINT   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    vote     SMALLINT NOT NULL CHECK (vote IN (-1, 1)),
    PRIMARY KEY (route_id, place_id, user_id)
);
