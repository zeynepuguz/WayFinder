-- Every user has a public username (shown under the photos they add): lowercase letters, digits, "_" and ".",
-- 3-20 characters, starting with a letter. Unique; users can change it in Profile.
ALTER TABLE users ADD COLUMN username VARCHAR(20);

-- Existing accounts: the first name in plain letters plus the account id ("zeynep7"), so every one is unique.
-- Turkish letters become their plain form; a name without any usable letter becomes "gezgin<id>".
WITH bases AS (
    SELECT id,
           left(regexp_replace(
                   translate(lower(split_part(trim(coalesce(first_name, display_name)), ' ', 1)),
                             'çğıiöşüâîûéèáàäëïíóòúùñ', 'cgiiosuaiueeaaaeiioouun'),
                   '[^a-z0-9]', '', 'g'), 12) AS base
    FROM users
)
UPDATE users u
SET username = (CASE WHEN b.base ~ '^[a-z][a-z0-9]{1,}$' THEN b.base ELSE 'gezgin' END) || u.id
FROM bases b
WHERE b.id = u.id;

ALTER TABLE users ALTER COLUMN username SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_username_key UNIQUE (username);
