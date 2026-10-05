# Nomi Backend (Spring Boot)

Nomi'nin ana backend'i: kullanıcılar, mekanlar, hava durumu, rota planlama, dinamik yeniden planlama ve AI asistan.

## Çalıştırma

```bash
# 1) Proje kökünde .env oluştur
cp .env.example .env        # JWT_SECRET, ADMIN_EMAIL, ADMIN_PASSWORD doldur

# 2) PostgreSQL + PostGIS ve Redis
docker compose up -d

# 3) Backend (backend/ klasöründe)
./mvnw spring-boot:run
```

- Şema Flyway ile otomatik kurulur (`src/main/resources/db/migration`). Mekanlar OpenStreetMap ve Overture'dan
  il il aktarılır (bkz. kök README "Mekan verisi"); geliştirmede `OSM_IMPORT_ON_STARTUP=false` ile başlatmak hızlıdır.
- Testler: `./mvnw test` (`WayfinderApplicationTests` çalışan Postgres + Redis ister).

## Mimari

| Paket | Görev |
|---|---|
| `controller`, `service`, `repository`, `entity`, `dto` | Klasik katmanlar |
| `planning` | Rota planlayıcı: PostGIS adayları → açık mı / bütçe → puanlama (rating, mesafe, saatlik hava, ilgi alanı) |
| `weather` | Open-Meteo istemcisi, Redis cache (15 dk), hava → plan kararları |
| `assistant` | Mesaj → intent (AI servisi ya da kural tabanlı Türkçe parser) → backend komutu → cevap |
| `security` | JWT (HS256), roller (USER / ADMIN) |
| `config` | Rate limit (Redis), request id loglama, cache hata toleransı, admin bootstrap |

Planı **LLM değil deterministik planlayıcı** üretir. Mekan, fiyat, mesafe, saat hep veritabanı/PostGIS/hava API'sinden gelir; LLM sadece isteği yapılandırılmış komuta çevirir.

## Endpoint'ler (`/api/v1`)

| Method | Yol | Auth | Açıklama |
|---|---|---|---|
| POST | `/auth/register`, `/auth/login` | – | JWT alır |
| GET / PUT | `/users/me`, `/users/me/preferences` | ✔ | Profil, yürüme toleransı, ilgi alanları |
| GET | `/places?category=&neighborhood=&maxCost=&indoor=&q=&page=&size=` | – | Keşfet |
| GET | `/places/nearby?lat=&lon=&radius=&limit=` | – | Yakındaki mekanlar (PostGIS) |
| GET | `/places/{id}` | – | Detay (çalışma saatleri, `openNow`) |
| POST / PUT / DELETE | `/places`, `/places/{id}` | ADMIN | Mekan yönetimi |
| GET | `/weather?lat=&lon=&date=` | – | Saatlik tahmin + plan tavsiyesi |
| GET | `/recommendations?lat=&lon=&type=&limit=` | opsiyonel | Şu an için öneri |
| GET | `/home?lat=&lon=` | opsiyonel | Ana sayfa: hava, öneriler, aktif rota |
| POST | `/routes` | ✔ | Günlük rota oluştur |
| GET | `/routes?saved=` | ✔ | Gezi Rotalarım / Kaydedilenler |
| GET / PATCH / DELETE | `/routes/{id}` | ✔ | Detay, kaydet, durum, başlık |
| PATCH | `/routes/{id}/stops/{stopId}` | ✔ | Durak: `VISITED` / `SKIPPED` |
| POST | `/routes/{id}/replan` | ✔ | `TIRED`, `WEATHER_CHANGED`, `REMOVE_STOP`, `REPLACE_STOP`, `ADD_STOP`, `ADD_INTEREST`, `LESS_WALKING` |
| GET / PUT / DELETE | `/saved/places`, `/saved/places/{placeId}` | ✔ | Kaydedilen mekanlar |
| POST | `/assistant/messages` | ✔ | `{message, latitude, longitude, routeId?}` |
| GET | `/assistant/messages?limit=` | ✔ | Sohbet geçmişi |
| GET | `/places/{id}/availability` | – | Google Places'a göre hâlâ açık mı (anahtar varsa) |
| POST | `/places/{id}/reports`, `/feedback` | ✔ | "Kapanmış" bildirimi, uygulama önerisi |
| GET / POST | `/admin/review/**` | ADMIN | Kaldır / şüpheli, silinen mekanlar, bildirimler, öneriler |
| GET | `/routes/popular?city=&district=&date=` | – | Popüler rotalar |
| GET | `/cities`, `/districts?city=` | – | 81 il ve ilçeleri |

Örnek rota isteği:

```json
POST /api/v1/routes
{ "latitude": 40.9910, "longitude": 29.0230, "partySize": 2, "budget": 700,
  "walkingTolerance": "LOW", "stops": ["BREAKFAST", "COFFEE", "DINNER"] }
```

## AI servisi sözleşmesi

`AI_SERVICE_URL` tanımlıysa backend `POST {AI_SERVICE_URL}/v1/intent` çağırır
(`X-API-Key: AI_SERVICE_API_KEY`). Gövde: `{ "message": "...", "context": { "hasRoute": true, "remainingStops": [...] } }`.
Cevap `assistant/AssistantIntent` şeklinde olmalıdır. Servis yoksa, hata verirse veya zaman aşımına uğrarsa
kural tabanlı parser kullanılır.
