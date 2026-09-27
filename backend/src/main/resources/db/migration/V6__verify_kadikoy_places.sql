-- Corrections to the V2 seed after a web check on 2026-09-27.
-- Coordinates come from OpenStreetMap (element ids in the comments). Hours are only kept
-- where a source backs them; where sources disagree, the window all of them agree on is used.
-- Places without reliable hours get no rows (= unknown). Prices stay estimates (TL per person).

-- Removed:
-- - Namlı Gurme: no Kadıköy branch (the Rıhtım Cd. shop is in Karaköy)
-- - Kadı Nimet Balıkçılık: no longer a fish restaurant, current operation unconfirmed
-- - Moda Sahnesi: a theatre; without show times it cannot be planned as a stop
DELETE FROM route_stops WHERE place_id IN (
    SELECT id FROM places WHERE name IN ('Namlı Gurme', 'Kadı Nimet Balıkçılık', 'Moda Sahnesi'));
DELETE FROM places WHERE name IN ('Namlı Gurme', 'Kadı Nimet Balıkçılık', 'Moda Sahnesi');

CREATE TEMPORARY TABLE place_fix (
    name        VARCHAR(255) PRIMARY KEY,
    new_name    VARCHAR(255),
    address     VARCHAR(255),
    lon         DOUBLE PRECISION,
    lat         DOUBLE PRECISION,
    description TEXT,
    category    VARCHAR(30),
    cost        INTEGER,
    indoor      BOOLEAN,
    minutes     INTEGER,
    source_url  VARCHAR(500)
) ON COMMIT DROP;

INSERT INTO place_fix VALUES
-- way 132474437. Now an İBB library with a Beltur café
('Tarihi Moda İskelesi', NULL, 'Moda İskele Cd., Caferağa', 29.02510, 40.97899,
 'Tarihi iskele binasında İBB kütüphanesi ve Beltur kafe; deniz manzaralı.', 'CAFE', 300, TRUE, 60,
 'https://kultursanat.istanbul/mekanlarimiz/tarihi-moda-iskelesi'),
-- way 1451594477
('Moda Çay Bahçesi', NULL, 'Ferit Tek Sk., Moda Burnu', 29.02108, 40.98075, NULL, NULL, NULL, NULL, NULL,
 'https://mekan.com/mekan/moda-cay-bahcesi'),
-- node 9869626517
('Çiya Sofrası', NULL, 'Güneşlibahçe Sk. No:43, Caferağa', 29.02443, 40.98935, NULL, NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/node/9869626517'),
-- node 2435483563
('Halil Lahmacun', NULL, 'Güneşlibahçe Sk. No:26, Caferağa', 29.02454, 40.98970,
 'Uzun yıllardır bilinen lahmacun salonu; pazartesi kapalı.', NULL, NULL, NULL, NULL,
 'https://www.instagram.com/halillahmacun/'),
-- node 9375681317 (Serasker Cd. branch; the seed's Moda Cd. branch does not exist)
('Borsam Taşfırın', NULL, 'Serasker Cd. No:78, Caferağa', 29.02701, 40.98963, NULL, NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/node/9375681317'),
-- node 2433671856
('Viktor Levi Şarap Evi', NULL, 'Damacı Sk. No:4, Moda', 29.02605, 40.98735, NULL, NULL, NULL, NULL, NULL,
 'https://www.viktorlevimoda.com/'),
-- node 5963740918
('Walter''s Coffee Roastery', NULL, 'Bademaltı Sk. No:21/B, Caferağa', 29.02606, 40.98477, NULL, NULL, NULL, NULL, NULL,
 'https://walterscoffee.com/pages/subelerimiz'),
-- node 13603023812
('Fazıl Bey''in Türk Kahvesi', NULL, 'Serasker Cd. No:1/A, Kadıköy Çarşı', 29.02476, 40.99039, NULL, NULL, NULL, NULL, NULL,
 'https://www.fazilbey.com.tr/'),
-- node 9375785317
('Montag Coffee Roasters', NULL, 'Bademaltı Sk. No:22/A, Caferağa', 29.02580, 40.98478, NULL, NULL, NULL, NULL, NULL,
 'https://www.montagcoffee.com/pages/moda'),
-- node 1394941165
('Baylan Pastanesi', NULL, 'Muvakkithane Cd. No:9/A, Kadıköy Çarşı', 29.02378, 40.99037, NULL, NULL, NULL, NULL, NULL,
 'http://www.baylangida.com/tr/iletisim'),
-- node 1394975639
('Hacı Bekir Kadıköy', NULL, 'Muvakkithane Cd. No:6/1, Kadıköy Çarşı', 29.02372, 40.99030, NULL, NULL, NULL, NULL, NULL,
 'https://www.hacibekir.com.tr/shops'),
-- node 1394959750
('Meşhur Dondurmacı Ali Usta', NULL, 'Moda Cd. No:176/B, Caferağa', 29.02283, 40.98157, NULL, NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/node/1394959750'),
-- way 668887438
('Akmar Pasajı', NULL, 'Mühürdar Cd. – Neşet Ömer Sk. arası', 29.02327, 40.98905,
 'Sahaf ve plakçılarıyla bilinen pasaj; dükkanların saatleri farklı.', NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/way/668887438'),
-- way 235498056
('Surp Takavor Ermeni Kilisesi', NULL, 'Muvakkithane Cd. No:44, Kadıköy Çarşı', 29.02442, 40.98987,
 'Tarihi Ermeni kilisesi. İç mekan her zaman açık olmayabilir; ziyaret saatleri doğrulanmadı.', NULL, NULL, FALSE, 15,
 'https://www.openstreetmap.org/way/235498056'),
-- way 179197257
('Aya Efimia Rum Ortodoks Kilisesi', NULL, 'Mühürdar Cd. No:3, Osmanağa', 29.02513, 40.99077,
 'Çarşı içindeki tarihi kilise. İç mekan her zaman açık olmayabilir; ziyaret saatleri doğrulanmadı.', NULL, NULL, FALSE, 15,
 'https://www.openstreetmap.org/way/179197257'),
-- way 235271667. Official: Tue–Sun 09:00–16:00, tickets online only
('Barış Manço Evi', NULL, 'Yusuf Kamil Paşa Sk. No:5, Caferağa', 29.02493, 40.98174,
 'Barış Manço''nun müzeye dönüştürülen evi. Biletler yalnızca online satılır; resmi tatillerde kapalı.', NULL, 82, NULL, NULL,
 'https://barismanco.kadikoy.bel.tr/iletisim'),
-- way 491935728. No public visits outside performances: shown as an exterior landmark
('Süreyya Operası', NULL, 'General Asım Gündüz Cd. No:29, Bahariye', 29.02903, 40.98802,
 '1927 yapımı tarihi opera binası. Binayı dışarıdan görebilirsin; salon yalnızca gösteri ve konserlerde açık.',
 'ATTRACTION', 0, FALSE, 15, 'https://sureyyaoperasi.kadikoy.bel.tr/'),
-- way 66321024. The museum is visited on the stadium tour; no tours on match days
('Fenerbahçe Müzesi', 'Fenerbahçe Stadyum ve Müze Turu', 'Chobani Stadyumu (Şükrü Saracoğlu), Zühtüpaşa', 29.03698, 40.98758,
 'Kulüp müzesi stadyum turuyla gezilir. Maç günleri ve resmi tatillerde tur yapılmaz; saatleri resmi sayfadan kontrol et.',
 NULL, 675, NULL, 75, 'https://www.fenerbahce.org/fenerbahceefsanesiturlari'),
-- way 132474420 (Moda Sahil Yolu)
('Moda Sahili', NULL, 'Moda Sahil Yolu, Caferağa', 29.02729, 40.97974, NULL, NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/way/132474420'),
-- way 66320567
('Yoğurtçu Parkı', NULL, 'Osmanağa Mh.', 29.03367, 40.98527, NULL, NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/way/66320567'),
-- way 51774213 (the seed point was inside the marina)
('Kalamış Parkı', NULL, 'Münir Nurettin Selçuk Cd., Fenerbahçe Mh.', 29.03819, 40.97983, NULL, NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/way/51774213'),
-- way 235392135 (the seed point was inside a military area; lighthouse access unverified)
('Fenerbahçe Parkı', NULL, 'Fenerbahçe Mh.', 29.03449, 40.96782,
 'Fenerbahçe burnunda deniz kenarında geniş park.', NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/way/235392135'),
-- node 6857143501
('Yeldeğirmeni Sokak Sanatı', NULL, 'Rasimpaşa Mh.', 29.02958, 40.99371, NULL, NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/node/6857143501'),
-- node 2892392880
('Kadıköy Boğa Heykeli', NULL, 'Altıyol, General Asım Gündüz Cd.', 29.02921, 40.99049, NULL, NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/node/2892392880'),
-- way 473151877. Under restoration since trains stopped in 2013; first phase targeted for 2027
('Haydarpaşa Garı', NULL, 'Tren Garı Cd., Rasimpaşa', 29.01884, 40.99620,
 'Tarihi gar binası restorasyonda, içeri girilemiyor; binayı rıhtımdan ve sahilden görebilirsin.', NULL, NULL, FALSE, 20,
 'https://www.takvim.com.tr/guncel/2026/09/23/haydarpasa-tren-garinda-ilk-etap-icin-hedef-2027'),
('Tarihi Kadıköy Çarşısı', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
 'https://www.openstreetmap.org/way/694715128');

UPDATE places p SET
    name              = COALESCE(f.new_name, p.name),
    address           = COALESCE(f.address, p.address),
    location          = CASE WHEN f.lon IS NULL THEN p.location
                             ELSE ST_SetSRID(ST_MakePoint(f.lon, f.lat), 4326)::geography END,
    description       = COALESCE(f.description, p.description),
    category          = COALESCE(f.category, p.category),
    estimated_cost    = COALESCE(f.cost, p.estimated_cost),
    indoor            = COALESCE(f.indoor, p.indoor),
    avg_visit_minutes = COALESCE(f.minutes, p.avg_visit_minutes),
    source            = 'WEB_CHECK',
    source_url        = f.source_url,
    last_verified_at  = TIMESTAMPTZ '2026-09-27 12:00:00+03',
    updated_at        = now()
FROM place_fix f
WHERE p.name = f.name;

-- Tags that no longer fit
UPDATE places SET tags = array_remove(tags, 'breakfast') WHERE name = 'Tarihi Moda İskelesi';
UPDATE places SET tags = array_remove(tags, 'view') WHERE name = 'Haydarpaşa Garı';

-- Opening hours: replace all seed hours with sourced ones
DELETE FROM place_opening_hours;

INSERT INTO place_opening_hours (place_id, day_of_week, opens_at, closes_at)
SELECT p.id, d, h.opens_at::time, h.closes_at::time
FROM (VALUES
        -- name, first day, last day (1 = Monday), opens, closes (closes <= opens = after midnight)
        ('Tarihi Moda İskelesi', 1, 7, '09:00', '22:00'),            -- café/restaurant/library sources overlap
        ('Moda Çay Bahçesi', 1, 7, '07:00', '00:00'),
        ('Çiya Sofrası', 1, 7, '11:30', '22:00'),                    -- OSM and Tripadvisor overlap
        ('Halil Lahmacun', 2, 7, '11:30', '20:00'),                  -- closed Mondays
        ('Viktor Levi Şarap Evi', 1, 7, '12:00', '01:00'),           -- official site
        ('Walter''s Coffee Roastery', 1, 6, '08:00', '23:00'),       -- official site
        ('Walter''s Coffee Roastery', 7, 7, '10:00', '22:00'),
        ('Fazıl Bey''in Türk Kahvesi', 1, 7, '08:00', '00:00'),      -- official site and OSM
        ('Montag Coffee Roasters', 1, 7, '09:00', '22:30'),          -- official site
        ('Baylan Pastanesi', 1, 7, '09:00', '20:00'),                -- listings overlap
        ('Hacı Bekir Kadıköy', 1, 7, '09:00', '21:00'),
        ('Meşhur Dondurmacı Ali Usta', 1, 7, '10:00', '01:00'),
        ('Barış Manço Evi', 2, 7, '09:00', '16:00')                  -- official; closed Mondays
     ) AS h (name, first_day, last_day, opens_at, closes_at)
JOIN places p ON p.name = h.name
CROSS JOIN LATERAL generate_series(h.first_day, h.last_day) AS d;
