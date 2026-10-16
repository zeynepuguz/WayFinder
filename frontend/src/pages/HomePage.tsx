import {
  ArrowRight, ArrowUp, CalendarDays, ChevronDown, ChevronRight, Cloud, CloudRain, Croissant, Crown, Landmark, Map as MapIcon,
  MapPin, Navigation, Route as RouteIcon, Search, Sunset, UserRound, type LucideIcon,
} from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { LEGAL_PATHS } from '../lib/legal'
import { api } from '../api'
import type { City, PlaceCategory } from '../api/types'
import { CitySheet, DistrictSheet } from '../components/AreaSheets'
import { useGate } from '../components/gate'
import { HScroll } from '../components/HScroll'
import { PlaceCard } from '../components/PlaceViews'
import { ErrorState, Skeleton } from '../components/ui'
import { CATEGORY_ICON, WEATHER_ICON } from '../components/visuals'
import { useAuth } from '../context/AuthContext'
import { useCity } from '../context/CityContext'
import { locationLabel, useUserLocation } from '../context/LocationContext'
import { CATEGORY_LABELS, isOpenRoute, locativeTr, STOP_TYPE_LABELS, todayIso } from '../lib/format'
import { isNativeApp } from '../lib/billing'
import { useAsync } from '../lib/useAsync'
import { useDistricts } from '../lib/districts'
import { setHomeArea, useHomeArea } from '../lib/homeArea'
import { locale, useT, type Translate } from '../lib/i18n'
import { LanguageSwitch } from '../components/LanguageSwitch'

type Idea = { title: string; text: string; prompt: string; icon: LucideIcon }

/**
 * One ready-made idea that makes sense now: by the time of day, indoors when it rains. Tapping it asks the assistant
 * for the place Ana Sayfa is about (without a pass: the paywall first, then the assistant with this idea).
 * where = "Kocaeli İzmit’te" / "in İzmit, Kocaeli" for a picked place; null = the device location (the assistant
 * already plans from there).
 */
export function ideaNow(t: Translate, where: { tr: string; en: string } | null, now: Date, rainy: boolean): Idea {
  const minutes = now.getHours() * 60 + now.getMinutes()
  const tr = (text: string) => (where ? `${where.tr} ${text.charAt(0).toLocaleLowerCase('tr')}${text.slice(1)}` : text)
  const en = (text: string) => (where ? `${text} ${where.en}` : text)
  const idea = {
    breakfast: {
      title: t('Kahvaltı & Gezi', 'Breakfast & Sights'),
      text: t('Kahvaltıyla başlayan bir sabah', 'A morning that starts with breakfast'),
      prompt: t(tr('Kahvaltıyla başlayan, yakındaki gezilecek yerlerle bir sabah rotası planla'), en('Plan a morning route that starts with breakfast and visits sights nearby')),
      icon: Croissant,
    },
    history: {
      title: t('Tarih & Kültür', 'History & Culture'),
      text: t('Müzeler, tarihi yerler ve aralarda mola', 'Museums, historic sights and breaks in between'),
      prompt: t(tr('Tarihi yerler ve müzeler ağırlıklı bir rota planla'), en('Plan a route focused on historic sights and museums')),
      icon: Landmark,
    },
    evening: {
      title: t('Akşamüstü Yürüyüşü', 'Evening Walk'),
      text: t('Yürüyüş, tatlı ve akşam yemeği', 'A walk, dessert and dinner'),
      prompt: t(tr('Akşamüstü yürüyüş, tatlı ve akşam yemeği içeren bir rota planla'), en('Plan an evening route with a walk, dessert and dinner')),
      icon: Sunset,
    },
    indoors: {
      title: t('Kapalı Mekanlar', 'Indoors Day'),
      text: t('Yağmurda müze, kafe ve kapalı yerler', 'Museums, cafés and indoor places in the rain'),
      prompt: t(tr('Yağmur olduğu için müze ve kafe gibi kapalı mekanlar ağırlıklı bir rota planla'), en('Plan a route of indoor places like museums and cafés because it is raining')),
      icon: CloudRain,
    },
    tomorrow: {
      title: t('Yarın İçin Plan', 'Plan for Tomorrow'),
      text: t('Kahvaltıdan akşama tam bir gün', 'A full day from breakfast to dinner'),
      prompt: t(tr('Yarın için tam günlük bir gezi rotası planla'), en('Plan a full day trip for tomorrow')),
      icon: CalendarDays,
    },
  }
  if (minutes >= 21 * 60 || minutes < 5 * 60) return idea.tomorrow
  if (rainy) return idea.indoors
  if (minutes < 11 * 60 + 30) return idea.breakfast
  if (minutes < 16 * 60) return idea.history
  return idea.evening
}

const CATEGORIES: PlaceCategory[] = ['CAFE', 'RESTAURANT', 'DESSERT', 'MUSEUM', 'PARK', 'ATTRACTION']

export function HomePage() {
  const { user, hasAccess } = useAuth()
  const location = useUserLocation()
  const cityState = useCity()
  const navigate = useNavigate()
  const gate = useGate()
  const t = useT()

  // Ana Sayfa's own place: the device location, or a city / district picked here (Keşfet's choice never changes it)
  const area = useHomeArea()
  const detected = useAsync(() => api.cityAt(location.latitude, location.longitude), [location.latitude, location.longitude],
    { cacheKey: `city-at:${location.latitude}:${location.longitude}` })
  const point = area ? area.district ?? area.city : location
  const areaCity: City | null = area?.city ?? (detected.data && detected.data.placeCount > 0 ? detected.data : null)
  const areaName = area ? (area.district ? `${area.district.name}, ${area.city.name}` : area.city.name) : detected.data?.name ?? null
  const where = area
    ? area.district
      ? { tr: `${area.city.name} ${locativeTr(area.district.name)}`, en: `in ${area.district.name}, ${area.city.name}` }
      : { tr: locativeTr(area.city.name), en: `in ${area.city.name}` }
    : null

  // Picking a place: the city first, then "Genel" (the whole city) or one of its districts
  const [cityOpen, setCityOpen] = useState(false)
  const [pickedCity, setPickedCity] = useState<City | null>(null)
  const districts = useDistricts(pickedCity?.slug ?? null)

  const { data, error, loading, reload } = useAsync(
    () => api.home(point.latitude, point.longitude),
    [point.latitude, point.longitude, user?.id],
    { cacheKey: `home:${user?.id ?? 0}:${point.latitude}:${point.longitude}` },
  )

  const hour = new Date().getHours()
  const greeting = hour < 5 ? t('İyi geceler', 'Good night') : hour < 12 ? t('Günaydın', 'Good morning') : hour < 18 ? t('İyi günler', 'Good afternoon') : t('İyi akşamlar', 'Good evening')

  function ask(prompt?: string) {
    // Always a new chat: /assistant alone is an empty chat, ?new=1&q= starts one with this prompt
    const target = prompt ? `/assistant?new=1&q=${encodeURIComponent(prompt)}` : '/assistant'
    if (gate(target)) navigate(target)
  }

  // Only today's route is "active"; a route of a past day never is (the backend already filters, this guards old servers)
  const today = todayIso()
  const activeRoute = data?.currentRoute && data.currentRoute.date >= today && isOpenRoute(data.currentRoute.status)
    ? data.currentRoute : null
  const tomorrowRoute = data?.tomorrowRoute && data.tomorrowRoute.date >= today && isOpenRoute(data.tomorrowRoute.status)
    ? data.tomorrowRoute : null

  const weather = data?.weather
  const WeatherIcon = weather ? WEATHER_ICON[weather.condition] ?? Cloud : Cloud
  const weatherTone = weather?.condition === 'RAIN' || weather?.condition === 'STORM' ? 'weather-rain'
    : weather && weather.apparentTemperature >= 30 ? 'weather-hot' : ''
  const idea = ideaNow(t, where, new Date(), weatherTone === 'weather-rain')
  const IdeaIcon = idea.icon

  return (
    <main className="screen">
      <section className="home-hero">
        {/* The place (left) and language / profile (right) on one line */}
        <div className="home-top">
          <div className="row wrap grow" style={{ gap: 8 }}>
            {area ? (
              <button className="location-chip" onClick={() => setCityOpen(true)} aria-label={`${t('Yer seç', 'Choose a place')}: ${areaName}`}>
                <MapPin size={13} /> {areaName}
              </button>
            ) : (
              <button className="location-chip" onClick={location.refresh}>
                <span className={`dot ${location.source === 'gps' ? 'dot-live' : ''}`} />
                <Navigation size={13} /> {locationLabel(location.source)}
              </button>
            )}
            {area ? (
              <button className="chip-link" onClick={() => setHomeArea(null)}>
                <Navigation size={14} /> {t('Konumumu kullan', 'Use my location')}
              </button>
            ) : (
              <button className="chip-link" onClick={() => setCityOpen(true)}>
                <MapPin size={14} /> {t('Yer seç', 'Choose a place')} <ChevronDown size={14} />
              </button>
            )}
          </div>
          <div className="row" style={{ gap: 8, flexShrink: 0 }}>
            <LanguageSwitch compact />
            <Link to="/profile" className="icon-btn" aria-label={t('Profil', 'Profile')}><UserRound size={20} /></Link>
          </div>
        </div>

        <div className="stack-sm">
          {areaName && <span className="t-overline">{areaName}</span>}
          <h1 className="t-display">
            {user
              ? `${greeting}, ${user.displayName.split(' ')[0]}`
              : areaCity
                ? t(`${locativeTr((area?.district ?? areaCity).name)} bugün ne yapsak?`, `What shall we do in ${(area?.district ?? areaCity).name} today?`)
                : t('Bugün ne yapsak?', 'What shall we do today?')}
          </h1>
        </div>

        <button className="ask-card" onClick={() => ask()}>
          <Search size={20} />
          {t('Bugün ne yapmak istersin?', 'What would you like to do today?')}
          <span className="go"><ArrowUp size={20} /></span>
        </button>
      </section>

      {error && <ErrorState message={error} onRetry={reload} />}

      {loading ? <Skeleton height={120} radius={14} /> : weather && (
        <section className={`weather ${weatherTone}`} aria-label={t('Hava durumu', 'Weather')}>
          <WeatherIcon size={34} strokeWidth={1.6} />
          <div className="row" style={{ alignItems: 'baseline', gap: 8, flexWrap: 'wrap' }}>
            <span className="weather-temp">{Math.round(weather.temperature)}°</span>
            <span className="weather-sub">
              {weather.conditionLabel.charAt(0).toLocaleUpperCase(locale()) + weather.conditionLabel.slice(1)}
              {' · '}{t('hissedilen', 'feels like')} {Math.round(weather.apparentTemperature)}°
            </span>
          </div>
          <p className="weather-advice">{weather.advice}</p>
        </section>
      )}

      {activeRoute ? (
        <Link to={`/routes/${activeRoute.id}`} className="card card-press active-route">
          <span className="pulse"><Navigation size={20} /></span>
          <div className="grow">
            <div className="t-overline" style={{ color: 'var(--brand-ink)' }}>{t('Aktif rotan', 'Your active route')}</div>
            <div className="t-headline">{activeRoute.title}</div>
            <div className="t-caption">{activeRoute.stopCount} {t('durak · devam etmek için dokun', 'stops · tap to continue')}</div>
          </div>
          <ChevronRight size={20} className="muted" />
        </Link>
      ) : tomorrowRoute && (
        <Link to={`/routes/${tomorrowRoute.id}`} className="card card-press active-route">
          <span className="pulse"><RouteIcon size={20} /></span>
          <div className="grow">
            <div className="t-overline" style={{ color: 'var(--brand-ink)' }}>{t('Yarınki rotan', 'Tomorrow’s route')}</div>
            <div className="t-headline">{tomorrowRoute.title}</div>
            <div className="t-caption">{tomorrowRoute.stopCount} {t('durak · görmek için dokun', 'stops · tap to view')}</div>
          </div>
          <ChevronRight size={20} className="muted" />
        </Link>
      )}

      <section className="section">
        <div className="section-head">
          <h2 className="t-title" style={{ fontSize: 20 }}>
            {data ? `${t('Şimdi için', 'Right now')}: ${STOP_TYPE_LABELS[data.suggestedStopType]}` : t('Şimdi için', 'Right now')}
          </h2>
          <Link to="/explore" className="section-link">{t('Tümü', 'See all')} <ChevronRight size={16} /></Link>
        </div>
        <HScroll>
          {loading && [0, 1].map(i => <Skeleton key={i} width={260} height={230} radius={14} />)}
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

      {/* One idea for right now; the assistant does the rest */}
      <button className="card card-press idea-now" onClick={() => ask(idea.prompt)}>
        <span className="idea-icon"><IdeaIcon size={22} /></span>
        <span className="grow">
          <span className="t-overline">{t('Şimdi için öneri', 'An idea for now')}</span>
          <span className="idea-title">{idea.title}</span>
          <span className="t-caption">{idea.text}</span>
        </span>
        {!hasAccess && <span className="badge badge-premium"><Crown size={12} /> Premium</span>}
        <ChevronRight size={20} className="muted" />
      </button>

      <section className="section">
        <h2 className="t-title" style={{ fontSize: 20 }}>{t('Kategoriler', 'Categories')}</h2>
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

      {!hasAccess && (
        <Link to="/premium" className="premium-callout">
          <Crown size={20} />
          <span className="grow">
            <strong>Nomi Premium</strong>
            {' · '}{t('Kişisel rotalar ve asistan, günlük 25 TL’den', 'Personal routes and assistant, from 25 TL a day')}
          </span>
          <ArrowRight size={18} />
        </Link>
      )}

      {data && (
        <p className="t-caption" style={{ textAlign: 'center' }}>
          <MapPin size={12} style={{ verticalAlign: -1 }} /> {t('Nomi Türkiye genelinde hizmet veriyor.', 'Nomi works across Türkiye.')}
        </p>
      )}

      <CitySheet open={cityOpen} onClose={() => setCityOpen(false)} selected={area?.city.slug ?? null} state={cityState}
                 myLocation={{ selected: area === null, onChoose: () => { setHomeArea(null); setCityOpen(false) } }}
                 onChoose={c => { setCityOpen(false); setPickedCity(c) }} />
      <DistrictSheet open={pickedCity !== null} onClose={() => setPickedCity(null)} districts={districts}
                     selected={area && pickedCity && area.city.slug === pickedCity.slug ? area.district?.slug ?? null : null}
                     allLabel={pickedCity ? t(`Genel – tüm ${pickedCity.name}`, `All of ${pickedCity.name}`) : undefined}
                     onChoose={d => { if (pickedCity) setHomeArea({ city: pickedCity, district: d }); setPickedCity(null) }} />

      {/* Website only: about and legal pages */}
      {!isNativeApp() && (
        <footer className="site-footer">
          <nav aria-label={t('Site bağlantıları', 'Site links')}>
            <Link to={LEGAL_PATHS.about}>{t('Hakkımızda ve İletişim', 'About & Contact')}</Link>
            <Link to={LEGAL_PATHS.terms}>{t('Kullanım Koşulları', 'Terms of Use')}</Link>
            <Link to={LEGAL_PATHS.privacy}>{t('Gizlilik Politikası', 'Privacy Policy')}</Link>
          </nav>
        </footer>
      )}
    </main>
  )
}
