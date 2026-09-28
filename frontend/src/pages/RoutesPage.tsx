import { Check, ChevronDown, LocateFixed, Map as MapIcon, MapPin, Plus } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { api } from '../api'
import type { City, District, RoutePlanRequest, RouteStartMode, StopType, WalkingTolerance } from '../api/types'
import { CitySheet, DistrictSheet } from '../components/AreaSheets'
import { Locked, useGate } from '../components/gate'
import { RouteCard } from '../components/RouteCard'
import { Alert, EmptyState, ErrorState, ListSkeleton, Segmented, Sheet, Spinner, Stepper } from '../components/ui'
import { STOP_ICON } from '../components/visuals'
import { useAuth } from '../context/AuthContext'
import { useCity } from '../context/CityContext'
import { useUserLocation } from '../context/LocationContext'
import { useDistricts } from '../lib/districts'
import { INTEREST_LABELS, isPastRoute, STOP_TYPE_LABELS, todayIso, WALKING_LABELS } from '../lib/format'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'

const STOP_ORDER: StopType[] = ['BREAKFAST', 'SIGHTSEEING', 'LUNCH', 'COFFEE', 'DESSERT', 'DINNER']

export function RoutesPage() {
  const { user } = useAuth()
  const t = useT()
  if (!user) {
    return (
      <main className="screen">
        <Locked icon={MapIcon} title={t('Rotaların burada', 'Your routes live here')}
                text={t('Oluşturduğun ve gezdiğin rotalar burada saklanır. Başlamak için giriş yap.', 'Routes you create and walk are kept here. Sign in to get started.')} />
      </main>
    )
  }
  return <RouteList />
}

function RouteList() {
  const gate = useGate()
  const t = useT()
  const [tab, setTab] = useState<'all' | 'saved'>('all')
  const [creating, setCreating] = useState(false)
  const { data, error, loading, reload } = useAsync(() => api.routes(tab === 'saved'), [tab])

  const openCreate = () => gate('/routes') && setCreating(true)
  const current = data?.filter(route => !isPastRoute(route)) ?? []
  const past = data?.filter(route => isPastRoute(route)) ?? []

  return (
    <main className="screen">
      <div className="row-between">
        <h1 className="t-display">{t('Rotalarım', 'My routes')}</h1>
        <button type="button" className="btn btn-primary btn-sm" onClick={openCreate}><Plus size={18} /> {t('Yeni rota', 'New route')}</button>
      </div>

      <Segmented value={tab} onChange={setTab} options={[{ value: 'all', label: t('Tümü', 'All') }, { value: 'saved', label: t('Kaydedilenler', 'Saved') }]} />

      {loading && <ListSkeleton rows={3} height={92} />}
      {error && <ErrorState message={error} onRetry={reload} />}
      {data?.length === 0 && (
        <EmptyState
          icon={MapIcon}
          title={tab === 'saved' ? t('Kaydedilen rota yok', 'No saved routes') : t('Henüz rotan yok', 'No routes yet')}
          text={tab === 'saved' ? t('Bir rotanın detayında kalbe dokunarak kaydedebilirsin.', 'Tap the heart on a route to save it.') : t('Birkaç tercihle ilk rotanı oluştur ya da asistana ne istediğini yaz.', 'Create your first route with a few choices, or tell the assistant what you’d like.')}
          action={tab === 'all' && <button type="button" className="btn btn-primary" onClick={openCreate}><Plus size={18} /> {t('Rota oluştur', 'Create route')}</button>}
        />
      )}
      {current.length > 0 && (
        <div className="stack">
          {current.map(route => <RouteCard key={route.id} route={route} />)}
        </div>
      )}
      {/* Routes of days that are over: read-only, never "active" */}
      {past.length > 0 && (
        <section className="section" aria-label={t('Geçmiş rotalar', 'Past routes')}>
          <h2 className="t-headline">{t('Geçmiş rotalar', 'Past routes')}</h2>
          <div className="stack">
            {past.map(route => <RouteCard key={route.id} route={route} />)}
          </div>
        </section>
      )}

      <Sheet open={creating} onClose={() => setCreating(false)} label={t('Yeni rota', 'New route')}>
        <NewRouteForm />
      </Sheet>
    </main>
  )
}

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
      })
      // The chosen interests travel along in case the server's route does not list them
      navigate(`/routes/${route.id}`, { state: { interests } })
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Rota oluşturulamadı', 'Could not create the route'))
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
