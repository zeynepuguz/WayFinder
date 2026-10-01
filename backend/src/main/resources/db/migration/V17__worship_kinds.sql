-- Explore > İbadet has sub-kinds: "mosque" (cami and mescit together), "church", "synagogue", "cemevi" as place tags.
-- New imports tag them (osm/PlaceTags.worshipKind); places already imported get the tag from their name here.

UPDATE places SET tags = array_append(tags, 'cemevi')
WHERE 'religious' = ANY (tags) AND NOT ('cemevi' = ANY (tags))
  AND lower(name) ~ 'cemev';

UPDATE places SET tags = array_append(tags, 'mosque')
WHERE 'religious' = ANY (tags) AND NOT ('mosque' = ANY (tags)) AND NOT ('cemevi' = ANY (tags))
  AND lower(name) ~ '(cami|mescid|mescit|mosque)';

UPDATE places SET tags = array_append(tags, 'church')
WHERE 'religious' = ANY (tags) AND NOT ('church' = ANY (tags))
  AND lower(name) ~ '(kilise|church|katedral|şapel|sapel|chapel|manastır|manastir)';

UPDATE places SET tags = array_append(tags, 'synagogue')
WHERE 'religious' = ANY (tags) AND NOT ('synagogue' = ANY (tags))
  AND lower(name) ~ '(sinagog|synagogue|havra)';
