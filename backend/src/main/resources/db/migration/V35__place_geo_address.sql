-- Full address of a place without a street address in its own data, looked up once from OpenStreetMap (Nominatim
-- reverse geocoding, ODbL) when its page is opened: "Dere Mah., Taşhan Sokak, 05100 Merkez/Amasya". Kept apart from
-- address, which the OSM / Overture imports rewrite. checked_at also marks lookups that found nothing (not asked again
-- for a month).
ALTER TABLE places ADD COLUMN geo_address VARCHAR(300);
ALTER TABLE places ADD COLUMN geo_address_checked_at TIMESTAMPTZ;
