-- An OSM place last edited years ago with no phone / website / opening hours (osm/OsmPlaceMapper.stale).
-- Only such places are hidden as "unconfirmed" when Overture does not know them either: Overture misses many real
-- places (BİM, A101 and half of the restaurants), so "not in Overture" alone hid far too much.
ALTER TABLE places ADD COLUMN osm_stale BOOLEAN NOT NULL DEFAULT FALSE;

-- The old rule's flags are void; the next import of each city sets them with the new rule
UPDATE places SET unconfirmed = FALSE WHERE unconfirmed;
