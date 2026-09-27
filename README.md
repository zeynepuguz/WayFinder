# Nomi — AI City Companion

Bulunduğun yerde, hava durumuna, bütçene ve tercihlerine göre günlük rota planlayan; gezi sırasında
"çok yorulduk", "yağmur başladı" dediğinde rotayı yeniden düzenleyen şehir asistanı. MVP: Kadıköy.

```
React (frontend/)  ──/api──►  Spring Boot (backend/)  ──►  PostgreSQL + PostGIS, Redis
                                     │
                                     └──POST /v1/intent──►  FastAPI (ai-service/)  ──►  OpenAI
```

- **backend/**: kullanıcılar, mekanlar, PostGIS aramaları, hava durumu, rota planlayıcı, replan, asistan. Ayrıntılar: [backend/README.md](backend/README.md)
- **ai-service/**: kullanıcının mesajını yapılandırılmış bir niyete (intent) çevirir. Mekan, fiyat, mesafe seçmez; onlar backend'de gerçek veriden gelir.
- **frontend/**: mobil öncelikli React + TypeScript arayüzü (Ana Sayfa, Asistan, Rotalarım, Keşfet, Kaydedilenler).

## Çalıştırma

```bash
cp .env.example .env              # JWT_SECRET, ADMIN_*, (opsiyonel) OPENAI_API_KEY, AI_SERVICE_*
docker compose up -d              # PostgreSQL + PostGIS (5433), Redis (6379)

cd backend && ./mvnw spring-boot:run                            # http://localhost:8080

cd ai-service                                                   # opsiyonel
py -3.12 -m venv .venv && .venv/Scripts/pip install -r requirements.txt
.venv/Scripts/uvicorn app.main:app --port 8000                  # http://localhost:8000/health

cd frontend && npm install && npm run dev                       # http://localhost:5173
```

AI servisi olmadan da her şey çalışır: `AI_SERVICE_URL` boşsa veya servis hata verirse backend kendi
kural tabanlı Türkçe parser'ını kullanır.

`.env` içindeki AI ayarları:

| Değişken | Değer |
|---|---|
| `AI_SERVICE_URL` | `http://localhost:8000` |
| `AI_SERVICE_API_KEY` | backend ↔ AI servisi arasında paylaşılan rastgele parola (`openssl rand -hex 32`) |
| `OPENAI_API_KEY` | OpenAI anahtarın (sadece AI servisi kullanır) |
| `OPENAI_MODEL` | opsiyonel, varsayılan `gpt-4.1-mini` (structured output destekleyen bir model) |

## Nomi Premium (ödeme)

Misafirler ana sayfa, hava durumu, keşfet ve mekan detaylarını görür. Asistan, rota oluşturma/değiştirme ve
kaydetme için giriş + aktif paket gerekir (backend 402 döner, uygulama paywall açar).

| Paket | Süre | Fiyat (ayar: `PRICE_*_TRY`) | Play Console ürün id |
|---|---|---|---|
| Günlük | 24 saat | 25 TL | `nomi_pass_daily` |
| Haftalık | 7 gün | 79 TL | `nomi_pass_weekly` |
| Aylık | 1 ay | 199 TL | `nomi_pass_monthly` |
| Yıllık | 1 yıl | 1.499 TL | `nomi_pass_yearly` |

- Otomatik yenilenmez; süre bitmeden alınan paket mevcut sürenin sonuna eklenir.
- Android'de ödeme **Google Play Billing** ile alınır (Play politikası gereği zorunlu). Uygulama satın alma
  token'ını backend'e yollar, backend Google Play Developer API ile doğrular ve ancak o zaman süreyi açar.
- **Web sitesi vitrindir:** mekanlar, kategoriler ve öneriler herkese açık; Premium webde satılmaz, yalnızca Android
  uygulamasında Google Play ile alınır. Premium'u olan hesap (uygulamadan alan ya da `FREE_ACCESS_EMAILS`) webde de
  her şeyi kullanır. `VITE_PLAY_STORE_URL` ayarlanınca web paywall'ı "Google Play'den indir" butonu gösterir.
- Yerelde store olmadan denemek için `BILLING_DEV_MODE=true` (production'da asla).
- `FREE_ACCESS_EMAILS`: virgülle ayrılmış e-postalar hiç ödeme yapmadan süresiz erişir (ör. uygulama sahibi). Sadece `.env`de tutulur.

### OpenAI maliyet koruması

- Asistan mesajı başına tek bir küçük OpenAI çağrısı yapılır (yalnızca niyet çıkarımı; rota, mekan ve hava
  OpenAI kullanmaz). `gpt-4.1-mini` ile mesaj başı yaklaşık 0,001 $.
- Kullanıcı başına günlük sınır: `AI_DAILY_LIMIT_PER_USER` (varsayılan 150, İstanbul gününe göre sıfırlanır).
  Sınırı aşan kullanıcı engellenmez; o gün kural tabanlı parser cevap verir. Botlara ve hesap paylaşımına karşıdır.
- Dakikada 30 istek sınırı ve mesaj başına 1000 karakter sınırı ayrıca geçerlidir.
- **OpenAI panelinde** (platform.openai.com → Settings → Limits) aylık bütçe limiti ve e-posta uyarısı koy.
  Limit dolarsa backend yine kural tabanlı parser'a düşer, uygulama çalışmaya devam eder.

### İadeler

Google Play'den iade edilen / ters ibraz edilen satın almalar saatte bir Voided Purchases API'den okunur ve
verdikleri paket `REVOKED` olur (`VoidedPurchaseSync`). Servis hesabı ayarlanana kadar iş hiçbir şey yapmaz.

## Dil: Türkçe / For tourists (English)

- Uygulama Türkçe ve İngilizcedir. Cihaz dili Türkçe değilse İngilizce açılır; "For tourists · English" düğmesi
  (ana sayfa, tanıtım ekranı, profil) ile değiştirilir, seçim hatırlanır. `?lang=en` bağlantısı da İngilizce açar.
- Metinler kullanıldıkları yerde iki dilli yazılır: `const t = useT(); t('Kaydedilenler', 'Saved')`
  (`frontend/src/lib/i18n.tsx`). Dil değişince uygulama yeniden kurulur, API istekleri `Accept-Language` gönderir.
- Backend asistan cevaplarını, rota notlarını, hava önerilerini ve mekan açıklamalarını (`places.description_en`)
  isteğin dilinde döner. Kural tabanlı parser temel İngilizce cümleleri de anlar.

## Şifremi unuttum

`POST /api/v1/auth/password/forgot` e-postaya 6 haneli kod yollar (15 dk geçerli, 5 deneme, dakikada 1 yeni kod);
`POST /api/v1/auth/password/reset` kod + yeni şifreyle şifreyi değiştirip oturum açar. Kayıtlı olmayan e-postaya da
aynı cevap döner. E-posta için bir SMTP sağlayıcısı gerekir (`MAIL_*`); yerelde `MAIL_DEV_LOG_CODES=true` ile kod
backend loguna yazılır.

## Mekan verisi

Kadıköy mekanları `V2` ile eklendi, `V6` ile 27.09.2026'da web üzerinden kontrol edildi: koordinatlar
OpenStreetMap'ten, çalışma saatleri yalnızca kaynağı olanlar için (kaynaklar çelişiyorsa hepsinin açık olduğu aralık).
Saati bilinmeyen mekanların satırı yoktur (planlayıcı "bilinmiyor" sayar). Fiyatlar tahminidir.

## Sunucuya kurulum (production)

`deploy/` klasörü tek komutla web sitesini, PostgreSQL/PostGIS, Redis, backend, AI servisi ve HTTPS'i (Caddy,
Let's Encrypt) kurar. Dışarıya yalnızca 80/443 açılır. Site, API ve yasal sayfalar aynı alan adındadır:

| Adres | Ne |
|---|---|
| `/`, `/places/{id}`, `/kadikoy/{kafe,restoran,…}` | Web sitesi; Google için önceden oluşturulmuş sayfalar |
| `/api/v1/...` | Backend |
| `/gizlilik`, `/kosullar`, `/iletisim` | Yasal sayfalar (`deploy/site/`) |
| `/sitemap.xml`, `/robots.txt` | Arama motorları için |

`web` servisi kurulumda backend hazır olunca mekanları okuyup her mekan ve kategori için başlık, açıklama ve
yapısal veri (schema.org) içeren HTML üretir (`frontend/scripts/prerender.mjs`). Mekan ekleyip değiştirdikten sonra:
`docker compose -f docker-compose.prod.yml run --rm web`.

```bash
# Linux sunucuda (Docker kurulu), alan adının DNS A kaydı sunucuya yönlenmiş olmalı
git clone <repo> && cd WayFinder/deploy
cp .env.prod.example .env                       # doldur: DOMAIN, şifreler, anahtarlar
mkdir -p secrets && cp <indirilen-json> secrets/play-service-account.json   # ilk çalıştırmadan ÖNCE
docker compose -f docker-compose.prod.yml up -d --build
curl https://<DOMAIN>/actuator/health            # {"status":"UP"}
```

- `deploy/site/` içindeki `[...]` yer tutucularını (geliştirici adı, destek e-postası, sağlayıcılar) doldur.
- Android uygulaması için: `frontend/.env.production` içinde `VITE_API_BASE_URL=https://<DOMAIN>/api/v1`,
  `VITE_PRIVACY_URL=https://<DOMAIN>/gizlilik`.
- Güncelleme: `git pull && docker compose -f docker-compose.prod.yml up -d --build`

### Rehberler ve İngilizce sayfalar

Aynı betik doğrulanmış mekan verisinden seyahat rehberleri de üretir (uygulama kodu yüklemeyen hızlı HTML,
`frontend/scripts/guides.mjs`). Her sayfanın Türkçe/İngilizce eşi `hreflang` ile bağlıdır:

| Türkçe | English |
|---|---|
| `/rehber/kadikoy-gezi-rehberi` | `/en/guides/things-to-do-in-kadikoy` |
| `/rehber/kadikoyde-yagmurlu-gunde-ne-yapilir` | `/en/guides/kadikoy-rainy-day` |
| `/rehber/kadikoyde-ucretsiz-gezilecek-yerler` | `/en/guides/free-things-to-do-in-kadikoy` |
| `/rehber/kadikoy-1-gunluk-gezi-rotasi` | `/en/guides/kadikoy-one-day-itinerary` |
| `/rehber/kadikoyde-kahvalti-ve-kahve` | `/en/guides/kadikoy-coffee-and-breakfast` |
| `/places/{id}`, `/kadikoy/{kategori}` | `/en/places/{id}`, `/en/kadikoy/{category}` |

Rehberlerde yalnızca veritabanındaki bilgiler (açıklama, adres, doğrulanmış saatler, tahmini fiyat) kullanılır;
İngilizce açıklaması olmayan mekanın İngilizce sayfasında açıklama gösterilmez.

### Google'da görünmek

1. Site yayındayken [Google Search Console](https://search.google.com/search-console)'a alan adını ekle (DNS TXT
   kaydıyla doğrula).
2. **Site haritaları** bölümüne `https://<DOMAIN>/sitemap.xml` ekle. Dizine girme birkaç gün–birkaç hafta sürer.
3. Google Business Profile ve sosyal medya hesaplarından siteye bağlantı vermek "Nomi Kadıköy" aramasında
   öne çıkmayı hızlandırır.

## Android uygulaması (Capacitor)

```bash
cd frontend
node scripts/android.mjs dev       # emülatör: API http://10.0.2.2:8080
node scripts/android.mjs release   # store: .env.production'daki HTTPS API
npx cap open android               # Android Studio'da aç, çalıştır / imzalı AAB üret
```

Play Store'a çıkmadan önce yapılacaklar:

1. Backend'i HTTPS ile yayınla (yukarıdaki "Sunucuya kurulum"); `frontend/.env.production` içine `VITE_API_BASE_URL` yaz.
2. Sunucu `.env`: `CORS_ALLOWED_ORIGINS=https://localhost` (uygulamanın WebView origin'i), `BILLING_DEV_MODE=false`.
3. Play Console'da uygulamayı `com.nomi.app` paket adıyla oluştur; yukarıdaki 4 ürünü **tüketilebilir (consumable)
   uygulama içi ürün** olarak tanımla.
4. Google Cloud'da servis hesabı oluştur, Play Console'da bu hesaba "Finansal verileri görüntüle / siparişleri yönet"
   izni ver, JSON anahtarının yolunu `GOOGLE_PLAY_SERVICE_ACCOUNT_FILE`'a yaz.
5. Gizlilik politikası ve kullanım koşulları: `deploy/site/` sayfalarını doldur, sunucuyla birlikte yayınlanır
   (`VITE_PRIVACY_URL`, `VITE_TERMS_URL`).
6. Harita için ticari kullanıma uygun bir karo sağlayıcısı (MapTiler, Stadia vb.) ve `VITE_MAP_TILE_URL`.
7. İmza anahtarı (upload key) oluştur, Android Studio'dan imzalı **AAB** üret, dahili test kanalına yükle.

## Testler

```bash
cd backend && ./mvnw test          # Postgres + Redis çalışıyor olmalı
cd ai-service && .venv/Scripts/python -m pytest
cd frontend && npm test
```
