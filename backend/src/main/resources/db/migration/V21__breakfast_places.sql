-- Börek / poğaça / simit shops and savoury bakeries are breakfast places, not cafés (osm/PlaceTags.breakfastAware);
-- breakfast places that are cafés are listed under both Kahvaltı and Kafe ("breakfast" / "cafe" tags)
WITH f AS (
    SELECT id, category, tags, lower(translate(name, 'İIıŞşĞğÜüÖöÇçÂâ', 'iiissgguuooccaa')) AS n FROM places
)
UPDATE places p SET category = 'BREAKFAST', updated_at = now()
FROM f
WHERE p.id = f.id
  AND (f.category = 'CAFE' AND ('bakery' = ANY (f.tags) OR f.n ~ '(borek|pogaca|simit|acma|gevrek|unlu ?mamul|katmer|firin)')
       OR f.category = 'RESTAURANT' AND 'quick' = ANY (f.tags) AND f.n ~ '(borek|pogaca|simit|acma|gevrek|unlu ?mamul|katmer)');

UPDATE places SET tags = array_append(tags, 'breakfast')
WHERE category = 'BREAKFAST' AND NOT ('breakfast' = ANY (tags));

UPDATE places SET tags = array_append(tags, 'cafe')
WHERE category = 'BREAKFAST' AND NOT ('cafe' = ANY (tags))
  AND lower(translate(name, 'İIıŞşĞğÜüÖöÇçÂâ', 'iiissgguuooccaa')) ~ '(cafe|kafe|caffe|coffee|kahve)';
