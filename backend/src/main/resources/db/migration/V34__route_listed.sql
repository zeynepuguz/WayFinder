-- A route the assistant plans stays in its chat until the user adds it to "Rotalarım" (listed = FALSE until then).
-- Every route made before this, and every route made from the new-route form, is listed.
ALTER TABLE routes ADD COLUMN listed BOOLEAN NOT NULL DEFAULT TRUE;
