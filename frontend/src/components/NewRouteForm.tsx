import { Check, ChevronDown, CloudRain, LocateFixed, Map as MapIcon, MapPin, Users, Wallet, type LucideIcon } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { api } from '../api'
import type { City, DayTheme, District, RoutePlanRequest, RouteStartMode, StopType, WalkingTolerance } from '../api/types'
import { useAuth } from '../context/AuthContext'
import { useCity } from '../context/CityContext'
import { useUserLocation } from '../context/LocationContext'
import { useDistricts } from '../lib/districts'
import { errorMessage, INTEREST_LABELS, STOP_TYPE_LABELS, todayIso, WALKING_LABELS } from '../lib/format'
import { useT, type Translate } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'
import { CitySheet, DistrictSheet } from './AreaSheets'
import { Alert, Segmented, Spinner, Stepper } from './ui'
import { STOP_ICON } from './visuals'

const STOP_ORDER: StopType[] = ['BREAKFAST', 'SIGHTSEEING', 'LUNCH', 'COFFEE', 'DESSERT', 'DINNER']

// What each theme does, said plainly (the server adds the same caveats to the route: planning/DayTheme)
function themes(t: Translate): { value: DayTheme; icon: LucideIcon; label: string; hint: string }[] {
  return [
    { value: 'RAINY', icon: CloudRain, label: t('Yağmurlu gün', 'Rainy day'),
      hint: t('Müze, kafe gibi kapalı mekanlar seçilir.', 'Indoor places such as museums and cafés.') },
    { value: 'LOW_BUDGET', icon: Wallet, label: t('Düşük bütçe', 'Low budget'),
      hint: t('Ücretsiz yerler ve uygun fiyatlı mekanlar öne alınır. Fiyat her mekan için bilinmediğinden tutarlar yaklaşıktır.',
        'Free sights and inexpensive places first. Prices are not known for every place, so amounts are rough.') },
    { value: 'FAMILY', icon: Users, label: t('Aile günü', 'Family day'),
      hint: t('Az yürüyüş, parklar ve tatlı molası. Çocuklara uygunluk her mekanda bilinmiyor.',
        'Less walking, parks and a dessert break. Child-friendliness is not known for every place.') },
  ]
}

// "Yeni rota": where to start, when, who, budget, walking, stops and interests -> a planned route
export function NewRouteForm() {
  const { user } = useAuth()
  const location = useUserLocation()
  const cityState = useCity()
  const navigate = useNavigate()
  const t = useT()
  const prefs = user?.preferences

  const [date, setDate] = useState(todayIso())
  const [startTime, setStartTime] = useState('')
  const [partySize, setPartySize] = useState(prefs?.defaultPartySize ?? 1)
  const [budget, setBudget] = useState(prefs?.defaultBudget?.toString() ?? '')
  const [walking, setWalking] = useState<WalkingTolerance>(prefs?.walkingTolerance ?? 'MEDIUM')
  const [stops, setStops] = useState<StopType[]>([])
  const [interests, setInterests] = useState<string[]>(prefs?.interests ?? [])
  const [theme, setTheme] = useState<DayTheme | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Where the route starts: the device position (only with a real GPS fix) or a chosen city / district
  const hasGps = location.source === 'gps'
  const [cityPick, setCityPick] = useState<string | null>(null)
  const [districtPick, setDistrictPick] = useState<District | null>(null)
  const [startChoice, setStartChoice] = useState<RouteStartMode | null>(null)
  const [cityOpen, setCityOpen] = useState(false)
  const [districtOpen, setDistrictOpen] = useState(false)
  const citySlug = cityPick ?? cityState.citySlug
  const cityName = cityState.cities.find(c => c.slug === citySlug)?.name ?? null
  // The city the user is in right now (null without GPS or outside a known city)
  const gpsCity = useAsync(
    () => (hasGps ? api.cityAt(location.latitude, location.longitude).then(c => c.slug, () => null) : Promise.resolve(null)),
    [hasGps],
  )
  // Default: from here when the user is in the chosen city, otherwise the chosen area
  const defaultMode: RouteStartMode = hasGps && gpsCity.data != null && gpsCity.data === citySlug ? 'LOCATION' : 'AREA'
  const startMode: RouteStartMode = hasGps ? startChoice ?? defaultMode : 'AREA'
  const districts = useDistricts(startMode === 'AREA' ? citySlug : null)

  const toggle = <T,>(list: T[], value: T) => (list.includes(value) ? list.filter(v => v !== value) : [...list, value])

  const chooseCity = (city: City) => {
    setCityOpen(false)
    // A new city starts without a district
    if (city.slug !== citySlug) setDistrictPick(null)
    setCityPick(city.slug)
    setStartChoice('AREA')
  }
  const chooseDistrict = (district: District | null) => {
    setDistrictOpen(false)
    setDistrictPick(district)
    setStartChoice('AREA')
  }

  function validate(): string | null {
    if (!date || date < todayIso()) {
      return t('Geçmiş bir gün için rota oluşturulamaz; bugünü ya da ileri bir tarihi seç.', 'You can’t plan a route for a past day; pick today or a later date.')
    }
    if (startMode === 'LOCATION' && !hasGps) return t('Konumun alınamadı; bir şehir ya da ilçe seç.', 'Your location isn’t available; choose a city or district.')
    if (startMode === 'AREA' && !citySlug) {
      return t('Rotanın nereden başlayacağını seç: konumun ya da bir şehir / ilçe.', 'Choose where the route starts: your location or a city / district.')
    }
    return null
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    const invalid = validate()
    if (invalid) {
      setError(invalid)
      return
    }
    setBusy(true)
    setError(null)
    // AREA: the server picks the start in the city / district, so no coordinates are sent
    const where: Pick<RoutePlanRequest, 'startMode' | 'latitude' | 'longitude' | 'city' | 'district'> = startMode === 'LOCATION'
      ? { startMode, latitude: location.latitude, longitude: location.longitude }
      : { startMode, city: citySlug ?? undefined, district: districtPick?.slug }
    try {
      const route = await api.planRoute({
        ...where,
        date,
        startTime: startTime || undefined,
        partySize,
        budget: budget ? Number(budget) : undefined,
        walkingTolerance: walking,
        stops: stops.length ? STOP_ORDER.filter(s => stops.includes(s)) : undefined,
        interests,
        theme: theme ?? undefined,
      })
      // The chosen interests travel along in case the server's route does not list them
      navigate(`/routes/${route.id}`, { state: { interests } })
    } catch (e) {
      setError(errorMessage(e, t('Rota oluşturulamadı', 'Could not create the route')))
      setBusy(false)
    }
  }

  const cityLabel = cityName ?? t('Şehir', 'City')
  const districtLabel = districtPick?.name ?? t('Tüm ilçeler', 'All districts')

  return (
    <>
      <form className="stack" style={{ gap: 20 }} onSubmit={submit} aria-busy={busy} noValidate>
        <div className="field">
          <span className="field-label">{t('Nereden başlayalım?', 'Where should we start?')}</span>
          <div className="start-options" role="radiogroup" aria-label={t('Başlangıç yeri', 'Starting point')}>
            {hasGps && (
              <button type="button" role="radio" aria-checked={startMode === 'LOCATION'}
                      className={`option ${startMode === 'LOCATION' ? 'active' : ''}`} onClick={() => setStartChoice('LOCATION')}>
                <LocateFixed size={20} /> {t('Konumumdan', 'From my location')}
              </button>
            )}
            <button type="button" role="radio" aria-checked={startMode === 'AREA'}
                    className={`option ${startMode === 'AREA' ? 'active' : ''}`} onClick={() => setStartChoice('AREA')}>
              <MapPin size={20} /> {t('Şehir / ilçe seç', 'Choose city / district')}
            </button>
          </div>
          {startMode === 'AREA' && (
            <div className="explore-controls" style={{ marginTop: 10 }}>
              <button type="button" className="chip city-pill active" onClick={() => setCityOpen(true)} aria-haspopup="dialog"
                      aria-label={`${t('Şehir seç', 'Choose city')}: ${cityLabel}`}>
                <MapPin size={15} /> <span>{cityLabel}</span> <ChevronDown size={15} />
              </button>
              <button type="button" className={`chip district-pill ${districtPick ? 'active' : ''}`} onClick={() => setDistrictOpen(true)}
                      disabled={!citySlug} aria-haspopup="dialog" aria-label={`${t('İlçe seç', 'Choose district')}: ${districtLabel}`}>
                <MapIcon size={15} /> <span>{districtLabel}</span> <ChevronDown size={15} />
              </button>
            </div>
          )}
          <span className="t-caption">
            {startMode === 'LOCATION'
              ? t('Rota bulunduğun yerden başlar.', 'The route starts where you are.')
              : districtPick
                ? t(`Rota ${districtPick.name} merkezinden başlar.`, `The route starts in central ${districtPick.name}.`)
                : cityName
                  ? t(`Rota ${cityName} merkezinden başlar; istersen bir ilçe seç.`, `The route starts in central ${cityName}; pick a district if you like.`)
                  : t('Bir şehir seç; istersen bir ilçe de seçebilirsin.', 'Choose a city, and a district if you like.')}
          </span>
        </div>

        <div className="field">
          <span className="field-label">{t('Günün teması', 'Theme of the day')} <span className="muted">· {t('isteğe bağlı', 'optional')}</span></span>
          <div className="option-grid">
            {themes(t).map(({ value, icon: Icon, label }) => (
              <button type="button" key={value} className={`option ${theme === value ? 'active' : ''}`} aria-pressed={theme === value}
                      onClick={() => {
                        const next = theme === value ? null : value
                        setTheme(next)
                        // Shown in the walking choice below, so the user sees it and can change it
                        if (next === 'FAMILY') setWalking('LOW')
                      }}>
                <Icon size={20} /> {label}
              </button>
            ))}
          </div>
          {theme && <span className="t-caption">{themes(t).find(x => x.value === theme)?.hint}</span>}
        </div>

        <div className="stack-sm">
          <div className="field-row">
            <label className="field">
              <span className="field-label">{t('Tarih', 'Date')}</span>
              <span className="input"><input type="date" value={date} min={todayIso()} onChange={e => setDate(e.target.value)} required /></span>
            </label>
            <label className="field">
              <span className="field-label">{t('Başlangıç saati', 'Start time')}</span>
              <span className="input">
                <input type="time" value={startTime} onChange={e => setStartTime(e.target.value)} aria-describedby="start-time-hint" />
              </span>
            </label>
          </div>
          <span id="start-time-hint" className="t-caption">
            {t('Saati boş bırakırsan en erken uygun saatten başlar.', 'Leave the time empty to start at the earliest suitable time.')}
          </span>
        </div>

        <div className="field-row">
          <div className="field">
            <span className="field-label">{t('Kişi sayısı', 'People')}</span>
            <Stepper value={partySize} min={1} max={20} onChange={setPartySize} label={t('Kişi sayısı', 'Number of people')} />
          </div>
          <label className="field">
            <span className="field-label">{t('Toplam bütçe', 'Total budget')}</span>
            <span className="input">
              {/* Enter only closes the keyboard: the route is created with the button */}
              <input type="number" inputMode="numeric" min={0} step={50} value={budget} placeholder={t('Sınırsız', 'No limit')}
                     enterKeyHint="done" onChange={e => setBudget(e.target.value)}
                     onKeyDown={e => {
                       if (e.key === 'Enter') {
                         e.preventDefault()
                         e.currentTarget.blur()
                       }
                     }} />
              TL
            </span>
          </label>
        </div>

        <div className="field">
          <span className="field-label">{t('Ne kadar yürüyelim?', 'How much walking?')}</span>
          <Segmented value={walking} onChange={setWalking}
                     options={(Object.keys(WALKING_LABELS) as WalkingTolerance[]).map(w => ({ value: w, label: WALKING_LABELS[w] }))} />
        </div>

        <div className="field">
          <span className="field-label">{t('Duraklar', 'Stops')} <span className="muted">· {t('seçmezsen tam gün', 'full day if none selected')}</span></span>
          <div className="option-grid">
            {STOP_ORDER.map(s => {
              const Icon = STOP_ICON[s]
              return (
                <button type="button" key={s} className={`option ${stops.includes(s) ? 'active' : ''}`}
                        aria-pressed={stops.includes(s)} onClick={() => setStops(toggle(stops, s))}>
                  <Icon size={20} /> {STOP_TYPE_LABELS[s]}
                </button>
              )
            })}
          </div>
        </div>

        <div className="field">
          <span className="field-label">
            {t('İlgi alanları', 'Interests')}{' '}
            <span className="muted">· {interests.length
              ? t(`${interests.length} seçili, rota bunlara öncelik verir`, `${interests.length} selected, the route favours these`)
              : t('seçersen rota bunlara göre kurulur', 'the route is built around what you pick')}</span>
          </span>
          <div className="chips">
            {Object.entries(INTEREST_LABELS).map(([key, label]) => {
              const on = interests.includes(key)
              return (
                <button type="button" key={key} className={`chip chip-interest ${on ? 'active' : ''}`}
                        aria-pressed={on} onClick={() => setInterests(toggle(interests, key))}>
                  {on && <Check size={15} />} {label}
                </button>
              )
            })}
          </div>
        </div>

        {error && <Alert tone="danger"><span>{error}</span></Alert>}
        <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={busy} aria-busy={busy}>
          {busy ? <><Spinner /> {t('Rotan hazırlanıyor', 'Preparing your route')}</> : t('Rotamı oluştur', 'Create my route')}
        </button>
      </form>

      {/* Outside the form (and every button is type="button"): picking a place never submits */}
      <CitySheet open={cityOpen} onClose={() => setCityOpen(false)} selected={citySlug} state={cityState} onChoose={chooseCity} />
      <DistrictSheet open={districtOpen} onClose={() => setDistrictOpen(false)} selected={districtPick?.slug ?? null}
                     districts={districts} onChoose={chooseDistrict} />
    </>
  )
}
