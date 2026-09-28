import {
  ArrowUp, ChevronRight, Crown, Landmark, Map as MapIcon, MapPin, Navigation, Route as RouteIcon, Sparkles, UserRound, Waves, Wallet, Coffee,
  type LucideIcon,
} from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { api } from '../api'
import type { PlaceCategory } from '../api/types'
import { useGate } from '../components/gate'
import { HScroll } from '../components/HScroll'
import { PlaceCard } from '../components/PlaceViews'
import { ErrorState, Skeleton } from '../components/ui'
import { BrandMark, CATEGORY_ICON, WEATHER_ICON } from '../components/visuals'
import { useAuth } from '../context/AuthContext'
import { useCity } from '../context/CityContext'
import { locationLabel, useUserLocation } from '../context/LocationContext'
import { CATEGORY_LABELS, locativeTr, STOP_TYPE_LABELS } from '../lib/format'
import { isNativeApp } from '../lib/billing'
import { useAsync } from '../lib/useAsync'
import { locale, useT } from '../lib/i18n'
import { LanguageSwitch } from '../components/LanguageSwitch'

type Translate = (turkish: string, english: string) => string

// Ready-made ideas: tapping one asks the assistant (paid) with this prompt
const ideas = (t: Translate): { title: string; text: string; prompt: string; icon: LucideIcon; gradient: string }[] => [
  {
    title: t('Kahve & Tatlı Turu', 'Coffee & Dessert Tour'),
    text: t('Nitelikli kahve ve tarihi pastaneler', 'Specialty coffee and historic patisseries'),
    prompt: t('Kahve ve tatlı ağırlıklı, az yürümeli bir rota planla', 'Plan a route focused on coffee and desserts, with little walking'),
    icon: Coffee,
    gradient: 'linear-gradient(145deg, #b98a61, #5e3f2a)',
  },
  {
    title: t('Tarihi Kadıköy', 'Historic Kadıköy'),
    text: t('Kiliseler, çarşı ve eski yapılar', 'Churches, the market and old buildings'),
    prompt: t('Tarihi yerler ağırlıklı bir günlük gezi planla, öğle yemeği de olsun', 'Plan a day trip focused on historic places, including lunch'),
    icon: Landmark,
    gradient: 'linear-gradient(145deg, #8f7cf8, #4a2bbd)',
  },
  {
    title: t('Uygun Bütçeli Gün', 'Day on a Budget'),
    text: t('2 kişi, 1000 TL ile tam gün', 'A full day for 2 with 1000 TL'),
    prompt: t('2 kişiyiz, 1000 TL bütçemiz var, uygun bütçeli bir günlük gezi planla', 'We are 2 people with a 1000 TL budget, plan an affordable day trip'),
    icon: Wallet,
    gradient: 'linear-gradient(145deg, #3fcf8e, #0a7a4b)',
  },
  {
    title: t('Moda’da Gün Batımı', 'Sunset in Moda'),
    text: t('Sahil yürüyüşü, dondurma, akşam yemeği', 'Seaside walk, ice cream, dinner'),
    prompt: t('Akşamüstü Moda sahilinde yürüyüş, tatlı ve akşam yemeği içeren bir rota planla', 'Plan an evening route with a walk along the Moda seafront, dessert and dinner'),
    icon: Waves,
    gradient: 'linear-gradient(145deg, #3fb6e8, #0b5f8f)',
  },
]

const CATEGORIES: PlaceCategory[] = ['CAFE', 'RESTAURANT', 'DESSERT', 'MUSEUM', 'PARK', 'ATTRACTION']

export function HomePage() {
  const { user, hasAccess } = useAuth()
  const location = useUserLocation()
  const { city, citySlug } = useCity()
  const navigate = useNavigate()
  const gate = useGate()
  const t = useT()

  const { data, error, loading, reload } = useAsync(
    () => api.home(location.latitude, location.longitude),
    [location.latitude, location.longitude, user?.id],
  )

  const hour = new Date().getHours()
  const greeting = hour < 5 ? t('İyi geceler', 'Good night') : hour < 12 ? t('Günaydın', 'Good morning') : hour < 18 ? t('İyi günler', 'Good afternoon') : t('İyi akşamlar', 'Good evening')

  function ask(prompt?: string) {
    const target = prompt ? `/assistant?q=${encodeURIComponent(prompt)}` : '/assistant'
    if (gate(target)) navigate(target)
  }

  const weather = data?.weather
  const WeatherIcon = weather ? WEATHER_ICON[weather.condition] ?? Sparkles : Sparkles
  const weatherTone = weather?.condition === 'RAIN' || weather?.condition === 'STORM' ? 'weather-rain'
    : weather && weather.apparentTemperature >= 30 ? 'weather-hot' : ''

  return (
    <main className="screen">
      <section className="home-hero">
        <div className="row-between">
          <BrandMark />
          <div className="row" style={{ gap: 8 }}>
            <LanguageSwitch compact />
            <Link to="/profile" className="icon-btn" aria-label={t('Profil', 'Profile')}><UserRound size={20} /></Link>
          </div>
        </div>

        <div className="stack" style={{ gap: 8 }}>
          <button className="location-chip" onClick={location.refresh} style={{ alignSelf: 'flex-start' }}>
            <span className={`dot ${location.source === 'gps' ? 'dot-live' : ''}`} />
            <Navigation size={13} /> {locationLabel(location.source)}
          </button>
          <h1 className="t-display">
            {user
              ? `${greeting}, ${user.displayName.split(' ')[0]}`
              : city
                ? t(`${locativeTr(city.name)} bugün ne yapsak?`, `What shall we do in ${city.name} today?`)
                : t('Bugün ne yapsak?', 'What shall we do today?')}
          </h1>
        </div>

        <button className="ask-card" onClick={() => ask()}>
          <Sparkles size={20} color="var(--brand)" />
          {t('Bugün ne yapmak istersin?', 'What would you like to do today?')}
          <span className="go"><ArrowUp size={20} /></span>
        </button>
      </section>

      {!hasAccess && (
        <Link to="/premium" className="card card-press row" style={{ gap: 14 }}>
          <span className="crown" style={{ width: 44, height: 44, borderRadius: 14 }}><Crown size={22} /></span>
          <div className="grow">
            <div className="t-headline" style={{ fontSize: 15 }}>Nomi Premium</div>
            <div className="t-caption">{t('Kişisel rotalar ve asistan · günlük 25 TL’den başlayan fiyatlar', 'Personal routes and assistant · from 25 TL a day')}</div>
          </div>
          <ChevronRight size={20} className="muted" />
        </Link>
      )}

      {error && <ErrorState message={error} onRetry={reload} />}

      {loading ? <Skeleton height={150} radius={20} /> : weather && (
        <section className={`weather ${weatherTone}`} aria-label={t('Hava durumu', 'Weather')}>
          <WeatherIcon size={52} strokeWidth={1.5} />
          <div>
            <div className="weather-temp">{Math.round(weather.temperature)}°</div>
            <div className="weather-sub">
              {weather.conditionLabel.charAt(0).toLocaleUpperCase(locale()) + weather.conditionLabel.slice(1)}
              {' · '}{t('hissedilen', 'feels like')} {Math.round(weather.apparentTemperature)}°
            </div>
          </div>
          <p className="weather-advice">{weather.advice}</p>
        </section>
      )}

      {data?.currentRoute && (
        <Link to={`/routes/${data.currentRoute.id}`} className="active-route card-press">
          <span className="pulse"><Navigation size={22} /></span>
          <div className="grow">
            <div className="t-overline" style={{ color: 'inherit', opacity: 0.6 }}>{t('Aktif rotan', 'Your active route')}</div>
            <div className="t-headline">{data.currentRoute.title}</div>
            <div style={{ fontSize: 13, opacity: 0.7 }}>{data.currentRoute.stopCount} {t('durak · devam etmek için dokun', 'stops · tap to continue')}</div>
          </div>
          <ChevronRight size={20} />
        </Link>
      )}

      <section className="section">
        <div className="section-head">
          <h2 className="t-headline">
            {data ? `${t('Şimdi için', 'Right now')}: ${STOP_TYPE_LABELS[data.suggestedStopType]}` : t('Şimdi için', 'Right now')}
          </h2>
          <Link to="/explore" className="section-link">{t('Tümü', 'See all')} <ChevronRight size={16} /></Link>
        </div>
        <HScroll>
          {loading && [0, 1].map(i => <Skeleton key={i} width={240} height={220} radius={20} />)}
          {data?.suggestions.map(s => (
            <PlaceCard key={s.place.id} place={s.place} reason={s.reasons.find(r => r.startsWith('Hava') || r.startsWith('Indoor place')) ?? s.reasons[0]} />
          ))}
        </HScroll>
        {data && data.suggestions.length === 0 && (
          <p className="t-caption">{t('Yakınında şu an açık bir öneri bulamadım.', 'I couldn’t find anything open near you right now.')}</p>
        )}
        <Link to="/explore?view=map" className="section-link" style={{ alignSelf: 'flex-start' }}>
          <MapIcon size={16} style={{ marginRight: 4 }} /> {t('Haritada gör', 'See on map')} <ChevronRight size={16} />
        </Link>
      </section>

      <section className="section">
        <div className="section-head">
          <h2 className="t-headline">{t('Rota fikirleri', 'Route ideas')}</h2>
          {!hasAccess && <span className="badge badge-premium"><Crown size={12} /> Premium</span>}
        </div>
        <HScroll>
          {ideas(t).map(({ title, text, prompt, icon: Icon, gradient }) => (
            <button key={title} className="idea-card" style={{ background: gradient }} onClick={() => ask(prompt)}>
              <span className="idea-icon"><Icon size={20} /></span>
              <div>
                <h3>{title}</h3>
                <p>{text}</p>
              </div>
            </button>
          ))}
        </HScroll>
        {/* Ready-made routes of the chosen city (free to look at; starting one needs Premium) */}
        <Link to={`/explore?${citySlug ? `sehir=${citySlug}&` : ''}rotalar=1`} className="card card-press row" style={{ gap: 14 }}>
          <span className="list-item-icon" style={{ background: 'var(--brand-50)', color: 'var(--brand)' }}><RouteIcon size={18} /></span>
          <div className="grow">
            <div className="t-headline" style={{ fontSize: 15 }}>{t('Popüler rotalar', 'Popular routes')}</div>
            <div className="t-caption">
              {city
                ? t(`${city.name} için hazır rotalar: tarih, lezzet, kahve, parklar`, `Ready-made routes for ${city.name}: history, food, coffee, parks`)
                : t('Hazır rotalar: tarih, lezzet, kahve, parklar', 'Ready-made routes: history, food, coffee, parks')}
            </div>
          </div>
          <ChevronRight size={20} className="muted" />
        </Link>
      </section>

      <section className="section">
        <h2 className="t-headline">{t('Kategoriler', 'Categories')}</h2>
        <div className="option-grid">
          {CATEGORIES.map(category => {
            const Icon = CATEGORY_ICON[category]
            return (
              <Link key={category} to={`/explore?category=${category}`} className="option">
                <Icon size={22} />
                {CATEGORY_LABELS[category]}
              </Link>
            )
          })}
        </div>
      </section>

      {data && (
        <p className="t-caption" style={{ textAlign: 'center' }}>
          <MapPin size={12} style={{ verticalAlign: -1 }} /> {t('Nomi Türkiye genelinde hizmet veriyor.', 'Nomi works across Türkiye.')}
        </p>
      )}

      {/* Website only: about and legal pages */}
      {!isNativeApp() && (
        <footer className="site-footer">
          <nav aria-label={t('Site bağlantıları', 'Site links')}>
            <a href={t('/rehber', '/en/')}>{t('Kadıköy rehberleri', 'Kadıköy travel guides')}</a>
            <a href="/iletisim">{t('Hakkımızda ve İletişim', 'About & Contact')}</a>
            <a href="/kosullar">{t('Kullanım Koşulları', 'Terms of Use')}</a>
            <a href="/gizlilik">{t('Gizlilik Politikası', 'Privacy Policy')}</a>
          </nav>
        </footer>
      )}
    </main>
  )
}
