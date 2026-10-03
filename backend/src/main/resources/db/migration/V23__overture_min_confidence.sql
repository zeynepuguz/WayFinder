-- Overture places added with confidence 0.5-0.7 (Oct 2026) included closed / misplaced ones ("Çayırova Kızılay Çay
-- Bahçesi", "Gebze Pusula Cafe" do not exist): back to 0.7, the rows below it are hidden (route stops keep them)
UPDATE places SET hidden = TRUE, updated_at = now()
WHERE source = 'OVERTURE' AND NOT hidden AND overture_confidence < 0.7;
