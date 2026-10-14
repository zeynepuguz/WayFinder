-- "Bu rotayı beğendin mi?": the route's owner likes or dislikes it. Liked routes are offered again (re-planned, so
-- closed places and rain are handled) to users who ask for a similar day nearby, and listed as the area's
-- community routes. Places in the same order count as one route however many users got it.
CREATE TABLE route_feedback (
    route_id   BIGINT PRIMARY KEY REFERENCES routes (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    liked      BOOLEAN     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_route_feedback_liked ON route_feedback (liked, updated_at);

-- The liked route a new route was built from (null = planned from scratch)
ALTER TABLE routes ADD COLUMN based_on_route_id BIGINT REFERENCES routes (id) ON DELETE SET NULL;
