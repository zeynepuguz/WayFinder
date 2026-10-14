-- Optional profile photo, shown with the username under the photos the user adds and on their public profile.
-- The files live next to the user photos (photo/PhotoStorage); the key is the file name without extension.
ALTER TABLE users ADD COLUMN avatar_key VARCHAR(64);
