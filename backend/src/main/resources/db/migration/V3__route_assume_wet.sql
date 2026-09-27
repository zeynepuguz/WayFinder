-- Remembers "Yağmur başladı" for the rest of the day, so later replans keep preferring indoor places
ALTER TABLE routes ADD COLUMN assume_wet BOOLEAN NOT NULL DEFAULT FALSE;
