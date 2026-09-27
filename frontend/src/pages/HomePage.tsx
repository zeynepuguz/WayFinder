import {
  ArrowUp, ChevronRight, Crown, Landmark, MapPin, Navigation, Sparkles, UserRound, Waves, Wallet, Coffee, type LucideIcon,
} from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { api } from '../api'
import type { PlaceCategory } from '../api/types'
import { useGate } from '../components/gate'
import { PlaceCard } from '../components/PlaceViews'
import { ErrorState, Skeleton } from '../components/ui'
import { BrandMark, CATEGORY_ICON, WEATHER_ICON } from '../components/visuals'
import { useAuth } from '../context/AuthContext'
import { locationLabel, useUserLocation } from '../context/LocationContext'
import { CATEGORY_LABELS, STOP_TYPE_LABELS } from '../lib/format'
import { useAsync } from '../lib/useAsync'

// Ready-made ideas: tapping one asks the assistant (paid) with this prompt
const IDEAS: { title: string; text: string; prompt: string; icon: LucideIcon; gradient: string }[] = [
  {
    title: 'Kahve & Tatlı Turu',
    text: 'Nitelikli kahve ve tarihi pastaneler',
    prompt: 'Kahve ve tatlı ağırlıklı, az yürümeli bir rota planla',
    icon: Coffee,
    gradient: 'linear-gradient(145deg, #b98a61, #5e3f2a)',
  },
  {
    title: 'Tarihi Kadıköy',
    text: 'Kiliseler, çarşı ve eski yapılar',
    prompt: 'Tarihi yerler ağırlıklı bir günlük gezi planla, öğle yemeği de olsun',
    icon: Landmark,
    gradient: 'linear-gradient(145deg, #8f7cf8, #4a2bbd)',
  },
  {
    title: 'Uygun Bütçeli Gün',
    text: '2 kişi, 1000 TL ile tam gün',
    prompt: '2 kişiyiz, 1000 TL bütçemiz var, uygun bütçeli bir günlük gezi planla',
    icon: Wallet,
    gradient: 'linear-gradient(145deg, #3fcf8e, #0a7a4b)',
  },
  {
    title: 'Moda’da Gün Batımı',
    text: 'Sahil yürüyüşü, dondurma, akşam yemeği',
    prompt: 'Akşamüstü Moda sahilinde yürüyüş, tatlı ve akşam yemeği içeren bir rota planla',
    icon: Waves,
    gradient: 'linear-gradient(145deg, #3fb6e8, #0b5f8f)',
  },
]

const CATEGORIES: PlaceCategory[] = ['CAFE', 'RESTAURANT', 'DESSERT', 'MUSEUM', 'PARK', 'ATTRACTION']

export function HomePage() {
  const { user, hasAccess } = useAuth()
  const location = useUserLocation()
  const navigate = useNavigate()
  const gate = useGate()

  const { data, error, loading, reload } = useAsync(
    () => api.home(location.latitude, location.longitude),
    [location.latitude, location.longitude, user?.id],
  )

  const hour = new Date().getHours()
  const greeting = hour < 5 ? 'İyi geceler' : hour < 12 ? 'Günaydın' : hour < 18 ? 'İyi günler' : 'İyi akşamlar'

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
          <Link to="/profile" className="icon-btn" aria-label="Profil"><UserRound size={20} /></Link>
        </div>

        <div className="stack" style={{ gap: 8 }}>
          <button className="location-chip" onClick={location.refresh} style={{ alignSelf: 'flex-start' }}>
            <span className={`dot ${location.source === 'gps' ? 'dot-live' : ''}`} />
            <Navigation size={13} /> {location.source === 'gps' ? 'Kadıköy, İstanbul' : locationLabel(location.source)}
          </button>
          <h1 className="t-display">
            {user ? `${greeting}, ${user.displayName.split(' ')[0]}` : 'Kadıköy’de bugün ne yapsak?'}
          </h1>
        </div>

        <button className="ask-card" onClick={() => ask()}>
          <Sparkles size={20} color="var(--brand)" />
          Bugün ne yapmak istersin?
          <span className="go"><ArrowUp size={20} /></span>
        </button>
      </section>

      {!hasAccess && (
        <Link to="/premium" className="card card-press row" style={{ gap: 14 }}>
          <span className="crown" style={{ width: 44, height: 44, borderRadius: 14 }}><Crown size={22} /></span>
          <div className="grow">
            <div className="t-headline" style={{ fontSize: 15 }}>Nomi Premium</div>
            <div className="t-caption">Kişisel rotalar ve asistan · günlük 25 TL’den başlayan fiyatlar</div>
          </div>
          <ChevronRight size={20} className="muted" />
        </Link>
      )}

      {error && <ErrorState message={error} onRetry={reload} />}

      {loading ? <Skeleton height={150} radius={20} /> : weather && (
        <section className={`weather ${weatherTone}`} aria-label="Hava durumu">
          <WeatherIcon size={52} strokeWidth={1.5} />
          <div>
            <div className="weather-temp">{Math.round(weather.temperature)}°</div>
            <div className="weather-sub">
              {weather.conditionLabel.charAt(0).toLocaleUpperCase('tr') + weather.conditionLabel.slice(1)}
              {' · '}hissedilen {Math.round(weather.apparentTemperature)}°
            </div>
          </div>
          <p className="weather-advice">{weather.advice}</p>
        </section>
      )}

      {data?.currentRoute && (
        <Link to={`/routes/${data.currentRoute.id}`} className="active-route card-press">
          <span className="pulse"><Navigation size={22} /></span>
          <div className="grow">
            <div className="t-overline" style={{ color: 'inherit', opacity: 0.6 }}>Aktif rotan</div>
            <div className="t-headline">{data.currentRoute.title}</div>
            <div style={{ fontSize: 13, opacity: 0.7 }}>{data.currentRoute.stopCount} durak · devam etmek için dokun</div>
          </div>
          <ChevronRight size={20} />
        </Link>
      )}

      <section className="section">
        <div className="section-head">
          <h2 className="t-headline">
            {data ? `Şimdi için: ${STOP_TYPE_LABELS[data.suggestedStopType]}` : 'Şimdi için'}
          </h2>
          <Link to="/explore" className="section-link">Tümü <ChevronRight size={16} /></Link>
        </div>
        <div className="h-scroll">
          {loading && [0, 1].map(i => <Skeleton key={i} width={240} height={220} radius={20} />)}
          {data?.suggestions.map(s => (
            <PlaceCard key={s.place.id} place={s.place} reason={s.reasons.find(r => r.startsWith('Hava')) ?? s.reasons[0]} />
          ))}
        </div>
        {data && data.suggestions.length === 0 && (
          <p className="t-caption">Yakınında şu an açık bir öneri bulamadım.</p>
        )}
      </section>

      <section className="section">
        <div className="section-head">
          <h2 className="t-headline">Rota fikirleri</h2>
          {!hasAccess && <span className="badge badge-premium"><Crown size={12} /> Premium</span>}
        </div>
        <div className="h-scroll">
          {IDEAS.map(({ title, text, prompt, icon: Icon, gradient }) => (
            <button key={title} className="idea-card" style={{ background: gradient }} onClick={() => ask(prompt)}>
              <span className="idea-icon"><Icon size={20} /></span>
              <div>
                <h3>{title}</h3>
                <p>{text}</p>
              </div>
            </button>
          ))}
        </div>
      </section>

      <section className="section">
        <h2 className="t-headline">Kategoriler</h2>
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
          <MapPin size={12} style={{ verticalAlign: -1 }} /> Nomi şu an Kadıköy’de hizmet veriyor.
        </p>
      )}
    </main>
  )
}
