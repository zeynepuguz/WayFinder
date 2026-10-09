-- Which device a session belongs to ("Chrome · Windows", "Nomi · Android") and the address it signed in from:
-- shown in the profile's open sessions and in the "signed in from a new device" e-mail
ALTER TABLE user_sessions ADD COLUMN device VARCHAR(80);
ALTER TABLE user_sessions ADD COLUMN ip_address VARCHAR(45);
