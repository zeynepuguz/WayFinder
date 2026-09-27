import { Map as MapIcon, Plus } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { api } from '../api'
import type { StopType, WalkingTolerance } from '../api/types'
import { Locked, useGate } from '../components/gate'
import { RouteCard } from '../components/RouteCard'
import { Alert, EmptyState, ErrorState, ListSkeleton, Segmented, Sheet, Spinner, Stepper } from '../components/ui'
import { STOP_ICON } from '../components/visuals'
import { useAuth } from '../context/AuthContext'
import { useUserLocation } from '../context/LocationContext'
import { INTEREST_LABELS, STOP_TYPE_LABELS, todayIso, WALKING_LABELS } from '../lib/format'
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

  return (
    <main className="screen">
      <div className="row-between">
        <h1 className="t-display">{t('Rotalarım', 'My routes')}</h1>
        <button className="btn btn-primary btn-sm" onClick={openCreate}><Plus size={18} /> {t('Yeni rota', 'New route')}</button>
      </div>

      <Segmented value={tab} onChange={setTab} options={[{ value: 'all', label: t('Tümü', 'All') }, { value: 'saved', label: t('Kaydedilenler', 'Saved') }]} />

      {loading && <ListSkeleton rows={3} height={92} />}
      {error && <ErrorState message={error} onRetry={reload} />}
      {data?.length === 0 && (
        <EmptyState
          icon={MapIcon}
          title={tab === 'saved' ? t('Kaydedilen rota yok', 'No saved routes') : t('Henüz rotan yok', 'No routes yet')}
          text={tab === 'saved' ? t('Bir rotanın detayında kalbe dokunarak kaydedebilirsin.', 'Tap the heart on a route to save it.') : t('Birkaç tercihle ilk rotanı oluştur ya da asistana ne istediğini yaz.', 'Create your first route with a few choices, or tell the assistant what you’d like.')}
          action={tab === 'all' && <button className="btn btn-primary" onClick={openCreate}><Plus size={18} /> {t('Rota oluştur', 'Create route')}</button>}
        />
      )}
      <div className="stack">
        {data?.map(route => <RouteCard key={route.id} route={route} />)}
      </div>

      <Sheet open={creating} onClose={() => setCreating(false)} label={t('Yeni rota', 'New route')}>
        <NewRouteForm />
      </Sheet>
    </main>
  )
}

function NewRouteForm() {
  const { user } = useAuth()
  const location = useUserLocation()
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

  const toggle = <T,>(list: T[], value: T) => (list.includes(value) ? list.filter(v => v !== value) : [...list, value])

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const route = await api.planRoute({
        latitude: location.latitude,
        longitude: location.longitude,
        date,
        startTime: startTime || undefined,
        partySize,
        budget: budget ? Number(budget) : undefined,
        walkingTolerance: walking,
        stops: stops.length ? STOP_ORDER.filter(s => stops.includes(s)) : undefined,
        interests,
      })
      navigate(`/routes/${route.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Rota oluşturulamadı', 'Could not create the route'))
      setBusy(false)
    }
  }

  return (
    <form className="stack" style={{ gap: 20 }} onSubmit={submit}>
      <div className="field-row">
        <label className="field">
          <span className="field-label">{t('Tarih', 'Date')}</span>
          <span className="input"><input type="date" value={date} min={todayIso()} onChange={e => setDate(e.target.value)} required /></span>
        </label>
        <label className="field">
          <span className="field-label">{t('Başlangıç', 'Start time')}</span>
          <span className="input"><input type="time" value={startTime} onChange={e => setStartTime(e.target.value)} /></span>
        </label>
      </div>

      <div className="field-row">
        <div className="field">
          <span className="field-label">{t('Kişi sayısı', 'People')}</span>
          <Stepper value={partySize} min={1} max={20} onChange={setPartySize} label={t('Kişi sayısı', 'Number of people')} />
        </div>
        <label className="field">
          <span className="field-label">{t('Toplam bütçe', 'Total budget')}</span>
          <span className="input">
            <input type="number" inputMode="numeric" min={0} step={50} value={budget} placeholder={t('Sınırsız', 'No limit')}
                   onChange={e => setBudget(e.target.value)} />
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
        <span className="field-label">{t('İlgi alanları', 'Interests')}</span>
        <div className="chips">
          {Object.entries(INTEREST_LABELS).map(([key, label]) => (
            <button type="button" key={key} className={`chip ${interests.includes(key) ? 'active' : ''}`}
                    aria-pressed={interests.includes(key)} onClick={() => setInterests(toggle(interests, key))}>
              {label}
            </button>
          ))}
        </div>
      </div>

      {error && <Alert tone="danger"><span>{error}</span></Alert>}
      <button className="btn btn-primary btn-lg btn-block" disabled={busy}>
        {busy ? <><Spinner /> {t('Rotan hazırlanıyor', 'Preparing your route')}</> : t('Rotamı oluştur', 'Create my route')}
      </button>
    </form>
  )
}
