-- Explore > Market sub-kinds (osm/PlaceTags.marketKind): the big chains by name, everything else "independent"
WITH f AS (
    SELECT id, ' ' || regexp_replace(lower(translate(name, 'İIıŞşĞğÜüÖöÇç', 'iiissgguuoocc')), '[^a-z0-9]+', ' ', 'g') || ' ' AS n
    FROM places WHERE category = 'MARKET'
)
UPDATE places p SET tags = array_append(p.tags,
        CASE WHEN f.n LIKE '% bim %' THEN 'bim'
             WHEN replace(f.n, ' ', '') LIKE '%a101%' THEN 'a101'
             WHEN f.n LIKE '% sok %' OR replace(f.n, ' ', '') LIKE 'sokmarket%' OR replace(f.n, ' ', '') LIKE '%sokmini%' THEN 'sok'
             WHEN replace(f.n, ' ', '') LIKE '%migros%' THEN 'migros'
             WHEN replace(f.n, ' ', '') LIKE '%hakmar%' THEN 'hakmar'
             ELSE 'independent' END)
FROM f
WHERE p.id = f.id AND NOT (p.tags && ARRAY['bim', 'a101', 'sok', 'migros', 'hakmar', 'independent']);
