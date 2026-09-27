-- MVP seed data for Kadıköy.
-- Coordinates, prices (TL per person) and opening hours are APPROXIMATE and must be verified
-- by the data pipeline / admin before production. That is why source = 'SEED_UNVERIFIED'
-- and last_verified_at is NULL.

INSERT INTO places (name, description, address, neighborhood, location, category, estimated_cost, rating, indoor, avg_visit_minutes, tags, source)
VALUES
-- Breakfast
('Namlı Gurme', 'Şarküteri ve serpme kahvaltı.', 'Rıhtım Cd.', 'Kadıköy Merkez',
 ST_GeogFromText('SRID=4326;POINT(29.0268 40.9912)'), 'BREAKFAST', 550, 4.3, TRUE, 60, '{breakfast,local}', 'SEED_UNVERIFIED'),
('Tarihi Moda İskelesi', 'Tarihi iskele binasında deniz manzaralı kafe-restoran.', 'Moda Sahili', 'Moda',
 ST_GeogFromText('SRID=4326;POINT(29.0215 40.9818)'), 'RESTAURANT', 650, 4.4, TRUE, 75, '{breakfast,sea,view,history}', 'SEED_UNVERIFIED'),
('Moda Çay Bahçesi', 'Moda burnunda deniz kenarında çay bahçesi.', 'Moda Burnu', 'Moda',
 ST_GeogFromText('SRID=4326;POINT(29.0242 40.9793)'), 'CAFE', 200, 4.5, FALSE, 60, '{breakfast,tea,sea,view,local}', 'SEED_UNVERIFIED'),

-- Restaurants
('Çiya Sofrası', 'Anadolu mutfağından yöresel yemekler.', 'Güneşlibahçe Sk.', 'Kadıköy Çarşı',
 ST_GeogFromText('SRID=4326;POINT(29.0262 40.9894)'), 'RESTAURANT', 600, 4.6, TRUE, 60, '{local,traditional}', 'SEED_UNVERIFIED'),
('Kadı Nimet Balıkçılık', 'Çarşı içinde balık restoranı.', 'Serasker Cd.', 'Kadıköy Çarşı',
 ST_GeogFromText('SRID=4326;POINT(29.0255 40.9896)'), 'RESTAURANT', 1200, 4.5, TRUE, 75, '{seafood,local}', 'SEED_UNVERIFIED'),
('Halil Lahmacun', 'Uzun yıllardır bilinen lahmacun salonu.', 'Caferağa Mh.', 'Kadıköy Merkez',
 ST_GeogFromText('SRID=4326;POINT(29.0278 40.9880)'), 'RESTAURANT', 300, 4.4, TRUE, 45, '{local,budget}', 'SEED_UNVERIFIED'),
('Borsam Taşfırın', 'Taş fırın lahmacun ve pide.', 'Moda Cd.', 'Moda',
 ST_GeogFromText('SRID=4326;POINT(29.0262 40.9848)'), 'RESTAURANT', 350, 4.3, TRUE, 45, '{local,budget}', 'SEED_UNVERIFIED'),
('Viktor Levi Şarap Evi', 'Bahçeli şarap evi ve restoran.', 'Moda Cd.', 'Moda',
 ST_GeogFromText('SRID=4326;POINT(29.0270 40.9858)'), 'RESTAURANT', 1300, 4.3, TRUE, 90, '{dinner,history}', 'SEED_UNVERIFIED'),

-- Coffee
('Walter''s Coffee Roastery', 'Tematik üçüncü dalga kahveci.', 'Caferağa Mh.', 'Moda',
 ST_GeogFromText('SRID=4326;POINT(29.0265 40.9855)'), 'CAFE', 220, 4.4, TRUE, 45, '{coffee}', 'SEED_UNVERIFIED'),
('Fazıl Bey''in Türk Kahvesi', 'Çarşı içinde klasik Türk kahvesi durağı.', 'Serasker Cd.', 'Kadıköy Çarşı',
 ST_GeogFromText('SRID=4326;POINT(29.0268 40.9900)'), 'CAFE', 130, 4.6, TRUE, 30, '{coffee,local,traditional}', 'SEED_UNVERIFIED'),
('Montag Coffee Roasters', 'Nitelikli kahve kavurucusu.', 'Moda', 'Moda',
 ST_GeogFromText('SRID=4326;POINT(29.0255 40.9840)'), 'CAFE', 230, 4.5, TRUE, 45, '{coffee}', 'SEED_UNVERIFIED'),

-- Dessert
('Baylan Pastanesi', '1923''ten beri hizmet veren tarihi pastane.', 'Muvakkithane Cd.', 'Kadıköy Çarşı',
 ST_GeogFromText('SRID=4326;POINT(29.0270 40.9905)'), 'DESSERT', 300, 4.4, TRUE, 40, '{dessert,history,traditional}', 'SEED_UNVERIFIED'),
('Hacı Bekir Kadıköy', 'Lokum ve akide şekeri.', 'Muvakkithane Cd.', 'Kadıköy Çarşı',
 ST_GeogFromText('SRID=4326;POINT(29.0260 40.9903)'), 'DESSERT', 200, 4.5, TRUE, 20, '{dessert,history,traditional}', 'SEED_UNVERIFIED'),
('Meşhur Dondurmacı Ali Usta', 'Moda''nın bilinen dondurmacısı.', 'Moda Cd.', 'Moda',
 ST_GeogFromText('SRID=4326;POINT(29.0260 40.9800)'), 'DESSERT', 150, 4.5, FALSE, 20, '{dessert,local}', 'SEED_UNVERIFIED'),

-- Sightseeing: outdoor
('Moda Sahili', 'Deniz kenarında yürüyüş ve gün batımı.', 'Moda', 'Moda',
 ST_GeogFromText('SRID=4326;POINT(29.0260 40.9798)'), 'PARK', 0, 4.7, FALSE, 60, '{sea,view,nature,walk}', 'SEED_UNVERIFIED'),
('Yoğurtçu Parkı', 'Mahalle parkı, gölgelik alanlar.', 'Caferağa Mh.', 'Yoğurtçu',
 ST_GeogFromText('SRID=4326;POINT(29.0370 40.9830)'), 'PARK', 0, 4.3, FALSE, 40, '{nature}', 'SEED_UNVERIFIED'),
('Kalamış Parkı', 'Marina kenarında geniş park.', 'Kalamış', 'Kalamış',
 ST_GeogFromText('SRID=4326;POINT(29.0390 40.9780)'), 'PARK', 0, 4.5, FALSE, 60, '{sea,nature,walk}', 'SEED_UNVERIFIED'),
('Fenerbahçe Parkı', 'Fenerbahçe burnunda deniz feneri ve park.', 'Fenerbahçe', 'Fenerbahçe',
 ST_GeogFromText('SRID=4326;POINT(29.0400 40.9690)'), 'PARK', 0, 4.7, FALSE, 60, '{sea,nature,view,walk}', 'SEED_UNVERIFIED'),
('Yeldeğirmeni Sokak Sanatı', 'Duvar resimleriyle bilinen tarihi mahalle.', 'Yeldeğirmeni', 'Yeldeğirmeni',
 ST_GeogFromText('SRID=4326;POINT(29.0290 40.9950)'), 'ATTRACTION', 0, 4.4, FALSE, 60, '{street-art,art,history,walk,local}', 'SEED_UNVERIFIED'),
('Kadıköy Boğa Heykeli', 'Altıyol''daki simge heykel.', 'Altıyol', 'Kadıköy Merkez',
 ST_GeogFromText('SRID=4326;POINT(29.0300 40.9870)'), 'ATTRACTION', 0, 4.2, FALSE, 15, '{history,landmark}', 'SEED_UNVERIFIED'),
('Haydarpaşa Garı', 'Tarihi tren garı binası ve rıhtım.', 'Haydarpaşa', 'Haydarpaşa',
 ST_GeogFromText('SRID=4326;POINT(29.0190 40.9970)'), 'ATTRACTION', 0, 4.6, FALSE, 40, '{history,architecture,view,sea}', 'SEED_UNVERIFIED'),
('Tarihi Kadıköy Çarşısı', 'Balıkçılar, şarküteriler ve yerel dükkanlar.', 'Kadıköy Çarşı', 'Kadıköy Çarşı',
 ST_GeogFromText('SRID=4326;POINT(29.0258 40.9898)'), 'ATTRACTION', 0, 4.6, FALSE, 60, '{local,shopping,food,history}', 'SEED_UNVERIFIED'),

-- Sightseeing: indoor
('Barış Manço Evi', 'Barış Manço''nun müzeye dönüştürülen evi.', 'Yusuf Kamil Paşa Sk.', 'Moda',
 ST_GeogFromText('SRID=4326;POINT(29.0300 40.9820)'), 'MUSEUM', 150, 4.6, TRUE, 45, '{museum,music,history}', 'SEED_UNVERIFIED'),
('Süreyya Operası', '1927 yapımı tarihi opera binası.', 'Bahariye Cd.', 'Bahariye',
 ST_GeogFromText('SRID=4326;POINT(29.0340 40.9880)'), 'CULTURE', 0, 4.7, TRUE, 30, '{history,architecture,art}', 'SEED_UNVERIFIED'),
('Moda Sahnesi', 'Bağımsız tiyatro ve sinema salonu.', 'Bahariye Cd.', 'Moda',
 ST_GeogFromText('SRID=4326;POINT(29.0282 40.9867)'), 'CULTURE', 350, 4.6, TRUE, 120, '{art,theatre}', 'SEED_UNVERIFIED'),
('Akmar Pasajı', 'Sahaf ve plakçılarıyla bilinen pasaj.', 'Neşet Ömer Sk.', 'Kadıköy Merkez',
 ST_GeogFromText('SRID=4326;POINT(29.0283 40.9893)'), 'ATTRACTION', 0, 4.4, TRUE, 40, '{books,local,shopping}', 'SEED_UNVERIFIED'),
('Surp Takavor Ermeni Kilisesi', 'Altıyol yakınındaki tarihi kilise.', 'Altıyol', 'Kadıköy Merkez',
 ST_GeogFromText('SRID=4326;POINT(29.0298 40.9878)'), 'ATTRACTION', 0, 4.5, TRUE, 20, '{history,architecture,religious}', 'SEED_UNVERIFIED'),
('Aya Efimia Rum Ortodoks Kilisesi', 'Çarşı içindeki tarihi kilise.', 'Yasa Cd.', 'Kadıköy Çarşı',
 ST_GeogFromText('SRID=4326;POINT(29.0255 40.9905)'), 'ATTRACTION', 0, 4.5, TRUE, 20, '{history,architecture,religious}', 'SEED_UNVERIFIED'),
('Fenerbahçe Müzesi', 'Şükrü Saracoğlu Stadyumu içindeki kulüp müzesi.', 'Kızıltoprak', 'Kızıltoprak',
 ST_GeogFromText('SRID=4326;POINT(29.0368 40.9876)'), 'MUSEUM', 200, 4.5, TRUE, 60, '{museum,sports}', 'SEED_UNVERIFIED');

-- Opening hours (every day)
INSERT INTO place_opening_hours (place_id, day_of_week, opens_at, closes_at)
SELECT p.id, d, h.opens_at::time, h.closes_at::time
FROM (VALUES
        ('Namlı Gurme', '07:00', '22:00'),
        ('Tarihi Moda İskelesi', '08:00', '00:00'),
        ('Moda Çay Bahçesi', '08:00', '23:00'),
        ('Çiya Sofrası', '11:00', '22:00'),
        ('Kadı Nimet Balıkçılık', '11:00', '23:00'),
        ('Halil Lahmacun', '11:00', '22:00'),
        ('Borsam Taşfırın', '10:00', '23:00'),
        ('Viktor Levi Şarap Evi', '12:00', '01:00'),
        ('Walter''s Coffee Roastery', '09:00', '23:00'),
        ('Fazıl Bey''in Türk Kahvesi', '08:00', '22:00'),
        ('Montag Coffee Roasters', '08:30', '21:00'),
        ('Baylan Pastanesi', '08:00', '22:00'),
        ('Hacı Bekir Kadıköy', '09:00', '21:00'),
        ('Meşhur Dondurmacı Ali Usta', '10:00', '01:00'),
        ('Moda Sahnesi', '12:00', '23:00'),
        ('Akmar Pasajı', '10:00', '20:00'),
        ('Surp Takavor Ermeni Kilisesi', '09:00', '17:00'),
        ('Aya Efimia Rum Ortodoks Kilisesi', '09:00', '17:00')
     ) AS h (name, opens_at, closes_at)
JOIN places p ON p.name = h.name
CROSS JOIN generate_series(1, 7) AS d;

-- Opening hours (closed on Mondays)
INSERT INTO place_opening_hours (place_id, day_of_week, opens_at, closes_at)
SELECT p.id, d, h.opens_at::time, h.closes_at::time
FROM (VALUES
        ('Barış Manço Evi', '10:00', '17:00'),
        ('Süreyya Operası', '10:00', '18:00'),
        ('Fenerbahçe Müzesi', '10:00', '18:00')
     ) AS h (name, opens_at, closes_at)
JOIN places p ON p.name = h.name
CROSS JOIN generate_series(2, 7) AS d;
