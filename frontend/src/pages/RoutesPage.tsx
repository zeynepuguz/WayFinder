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
import { useAsync } from '../lib/useAsync'

const STOP_ORDER: StopType[] = ['BREAKFAST', 'SIGHTSEEING', 'LUNCH', 'COFFEE', 'DESSERT', 'DINNER']

export function RoutesPage() {
  const { user } = useAuth()
  if (!user) {
    return (
      <main className="screen">
        <Locked icon={MapIcon} title="Rotaların burada"
                text="Oluşturduğun ve gezdiğin rotalar burada saklanır. Başlamak için giriş yap." />
      </main>
    )
  }
  return <RouteList />
}

function RouteList() {
  const gate = useGate()
  const [tab, setTab] = useState<'all' | 'saved'>('all')
  const [creating, setCreating] = useState(false)
  const { data, error, loading, reload } = useAsync(() => api.routes(tab === 'saved'), [tab])

  const openCreate = () => gate('/routes') && setCreating(true)

  return (
    <main className="screen">
      <div className="row-between">
        <h1 className="t-display">Rotalarım</h1>
        <button className="btn btn-primary btn-sm" onClick={openCreate}><Plus size={18} /> Yeni rota</button>
      </div>

      <Segmented value={tab} onChange={setTab} options={[{ value: 'all', label: 'Tümü' }, { value: 'saved', label: 'Kaydedilenler' }]} />

      {loading && <ListSkeleton rows={3} height={92} />}
      {error && <ErrorState message={error} onRetry={reload} />}
      {data?.length === 0 && (
        <EmptyState
          icon={MapIcon}
          title={tab === 'saved' ? 'Kaydedilen rota yok' : 'Henüz rotan yok'}
          text={tab === 'saved' ? 'Bir rotanın detayında kalbe dokunarak kaydedebilirsin.' : 'Birkaç tercihle ilk rotanı oluştur ya da asistana ne istediğini yaz.'}
          action={tab === 'all' && <button className="btn btn-primary" onClick={openCreate}><Plus size={18} /> Rota oluştur</button>}
        />
      )}
      <div className="stack">
        {data?.map(route => <RouteCard key={route.id} route={route} />)}
      </div>

      <Sheet open={creating} onClose={() => setCreating(false)} label="Yeni rota">
        <NewRouteForm />
      </Sheet>
    </main>
  )
}

function NewRouteForm() {
  const { user } = useAuth()
  const location = useUserLocation()
  const navigate = useNavigate()
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
      setError(e instanceof Error ? e.message : 'Rota oluşturulamadı')
      setBusy(false)
    }
  }

  return (
    <form className="stack" style={{ gap: 20 }} onSubmit={submit}>
      <div className="field-row">
        <label className="field">
          <span className="field-label">Tarih</span>
          <span className="input"><input type="date" value={date} min={todayIso()} onChange={e => setDate(e.target.value)} required /></span>
        </label>
        <label className="field">
          <span className="field-label">Başlangıç</span>
          <span className="input"><input type="time" value={startTime} onChange={e => setStartTime(e.target.value)} /></span>
        </label>
      </div>

      <div className="field-row">
        <div className="field">
          <span className="field-label">Kişi sayısı</span>
          <Stepper value={partySize} min={1} max={20} onChange={setPartySize} label="Kişi sayısı" />
        </div>
        <label className="field">
          <span className="field-label">Toplam bütçe</span>
          <span className="input">
            <input type="number" inputMode="numeric" min={0} step={50} value={budget} placeholder="Sınırsız"
                   onChange={e => setBudget(e.target.value)} />
            TL
          </span>
        </label>
      </div>

      <div className="field">
        <span className="field-label">Ne kadar yürüyelim?</span>
        <Segmented value={walking} onChange={setWalking}
                   options={(Object.keys(WALKING_LABELS) as WalkingTolerance[]).map(w => ({ value: w, label: WALKING_LABELS[w] }))} />
      </div>

      <div className="field">
        <span className="field-label">Duraklar <span className="muted">· seçmezsen tam gün</span></span>
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
        <span className="field-label">İlgi alanları</span>
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
        {busy ? <><Spinner /> Rotan hazırlanıyor</> : 'Rotamı oluştur'}
      </button>
    </form>
  )
}
