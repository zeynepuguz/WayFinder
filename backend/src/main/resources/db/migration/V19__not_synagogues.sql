-- "Havra" matched inside other words ("Havraniye Mah.", "Havran"): such rows are no synagogues (osm/PlaceTags now
-- matches whole words). Deleted when nothing references them, else hidden
DELETE FROM places p
WHERE p.source = 'OSM' AND 'synagogue' = ANY (p.tags)
  AND p.name ~* 'havra' AND p.name !~* '\mhavra(s[iı])?\M'
  AND NOT EXISTS (SELECT 1 FROM route_stops rs WHERE rs.place_id = p.id)
  AND NOT EXISTS (SELECT 1 FROM saved_places sp WHERE sp.place_id = p.id)
  AND NOT EXISTS (SELECT 1 FROM user_photos up WHERE up.place_id = p.id);

UPDATE places SET hidden = TRUE, updated_at = now()
WHERE source = 'OSM' AND 'synagogue' = ANY (tags) AND NOT hidden
  AND name ~* 'havra' AND name !~* '\mhavra(s[iı])?\M';
