-- Places the user swapped out of a route ("Başka bir yerle değiştir"): never planned into that route again, so a
-- second swap does not bring the first place back
ALTER TABLE routes ADD COLUMN rejected_place_ids BIGINT[] NOT NULL DEFAULT '{}';
