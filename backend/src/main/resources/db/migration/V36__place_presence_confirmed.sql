-- The owner checked that the place is there ("Bu yer var" on the place page): Google not knowing it (parks,
-- squares and other places that are no business are often missing from Places search) no longer warns about it.
-- Kept apart from review: places with any review are not planned.
ALTER TABLE places ADD COLUMN presence_confirmed_at TIMESTAMPTZ;
