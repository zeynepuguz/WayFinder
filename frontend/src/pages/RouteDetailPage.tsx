import {
  BatteryLow, Check, ChevronDown, CloudRain, Footprints, Heart, Plus, RefreshCw, Shuffle, Trash2, TriangleAlert, Wallet,
  Clock, Ellipsis, SkipForward, X,
} from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api } from '../api'
import type { ReplanRequest, Route, RouteStop, StopStatus, StopType } from '../api/types'
import { useGate } from '../components/gate'
import { RouteMap } from '../components/RouteMap'
import { Alert, BackButton, ErrorState, Sheet, Skeleton, Spinner, useToast } from '../components/ui'
import { STOP_ICON, WEATHER_ICON } from '../components/visuals'
import { useUserLocation } from '../context/LocationContext'
import { formatCost, formatDate, formatDistance, formatTime, INTEREST_LABELS, STOP_TYPE_LABELS } from '../lib/format'
import { useAsync } from '../lib/useAsync'

type Change = Omit<ReplanRequest, 'latitude' | 'longitude'>

export function RouteDetailPage() {
  const { id } = useParams()
  const routeId = Number(id)
  const navigate = useNavigate()
  const location = useUserLocation()
  const gate = useGate()
  const toast = useToast()

  const { data: route, setData: setRoute, error, loading, reload } = useAsync(() => api.route(routeId), [routeId])
  const [busy, setBusy] = useState(false)
  const [changes, setChanges] = useState<string[]>([])
  const [actionError, setActionError] = useState<string | null>(null)
  const [addOpen, setAddOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [menuStop, setMenuStop] = useState<RouteStop | null>(null)

  async function act(action: () => Promise<Route | { route: Route; changes: string[] }>, gated = true) {
    if (gated && !gate()) return
    setBusy(true)
    setActionError(null)
    try {
      const result = await action()
      if ('changes' in result) {
        setRoute(result.route)
        setChanges(result.changes)
        window.scrollTo({ top: 0, behavior: 'smooth' })
      } else {
        setRoute(result)
      }
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'İşlem başarısız')
    } finally {
      setBusy(false)
    }
  }

  const replan = (change: Change) =>
    act(() => api.replan(routeId, { ...change, latitude: location.latitude, longitude: location.longitude }))
  const setStopStatus = (stop: RouteStop, status: StopStatus) => act(() => api.updateStop(routeId, stop.id, status))

  async function remove() {
    await api.deleteRoute(routeId)
    toast('Rota silindi')
    navigate('/routes', { replace: true })
  }

  if (loading) {
    return (
      <main className="screen screen-flush">
        <Skeleton height={300} radius={0} />
        <div className="pad stack"><Skeleton height={28} width="60%" /><Skeleton height={80} radius={16} /></div>
      </main>
    )
  }
  if (error || !route) {
    return <main className="screen"><BackButton to="/routes" /><ErrorState message={error ?? 'Rota bulunamadı'} onRetry={reload} /></main>
  }

  const finished = route.status === 'COMPLETED'
  const WeatherIcon = WEATHER_ICON[route.weather.condition ?? ''] ?? CloudRain
  const nextStop = route.stops.find(s => s.status === 'PLANNED')

  return (
    <main className="screen screen-flush" style={{ paddingTop: 0 }}>
      <div style={{ position: 'relative' }}>
        <RouteMap
          hero
          height={300}
          start={{ latitude: route.startLatitude, longitude: route.startLongitude }}
          points={route.stops.map((s, i) => ({
            latitude: s.place.latitude, longitude: s.place.longitude, label: String(i + 1),
            title: `${formatTime(s.plannedStart)} · ${s.place.name}`, muted: s.status !== 'PLANNED',
          }))}
        />
        <div className="place-hero-bar" style={{ top: 'calc(var(--safe-top) + 12px)', zIndex: 500 }}>
          <BackButton to="/routes" glass />
          <button className="icon-btn icon-btn-glass" disabled={busy} aria-pressed={route.saved}
                  aria-label={route.saved ? 'Kaydedilenlerden çıkar' : 'Rotayı kaydet'}
                  onClick={() => act(() => api.updateRoute(routeId, { saved: !route.saved }))}>
            <Heart size={20} fill={route.saved ? '#ff5a36' : 'none'} color={route.saved ? '#ff5a36' : 'currentColor'} />
          </button>
        </div>
      </div>

      <div className="place-sheet pad">
        <div className="stack-sm">
          <span className="t-overline">{formatDate(route.date)} · {route.partySize} kişi</span>
          <h1 className="t-title">{route.title}</h1>
        </div>

        <div className="stats">
          <div className="stat"><Wallet size={16} className="muted" /><strong>~{route.totalEstimatedCost.toLocaleString('tr-TR')} TL</strong>
            <span>{route.budget != null ? `bütçe ₺${route.budget.toLocaleString('tr-TR')}` : 'tahmini'}</span></div>
          <div className="stat"><Footprints size={16} className="muted" /><strong>~{route.totalWalkingMinutes} dk</strong><span>yürüme</span></div>
          <div className="stat"><Clock size={16} className="muted" /><strong>{route.stops.length}</strong><span>durak</span></div>
        </div>

        {changes.length > 0 && (
          <Alert tone="success" icon={RefreshCw} action={
            <button className="icon-btn icon-btn-plain" style={{ width: 28, height: 28 }} aria-label="Kapat" onClick={() => setChanges([])}><X size={16} /></button>
          }>
            <strong>Rotan güncellendi</strong>
            <ul>{changes.map(c => <li key={c}>{c}</li>)}</ul>
          </Alert>
        )}
        {actionError && <Alert tone="danger"><span>{actionError}</span></Alert>}

        {route.weather.advice && (
          <Alert tone="info" icon={WeatherIcon}><span>{route.weather.advice}</span></Alert>
        )}
        {route.notes.map(note => <Alert key={note} tone="warning" icon={TriangleAlert}><span>{note}</span></Alert>)}

        {!finished && (
          <section className="section">
            <div className="row-between">
              <h2 className="t-headline">Planı değiştir</h2>
              {busy && <Spinner />}
            </div>
            <div className="quick-actions">
              <button className="quick-action" disabled={busy} onClick={() => replan({ type: 'TIRED' })}>
                <span className="qa-icon"><BatteryLow size={20} /></span>Yorulduk
              </button>
              <button className="quick-action" disabled={busy} onClick={() => replan({ type: 'WEATHER_CHANGED' })}>
                <span className="qa-icon"><CloudRain size={20} /></span>Yağmur başladı
              </button>
              <button className="quick-action" disabled={busy} onClick={() => replan({ type: 'LESS_WALKING' })}>
                <span className="qa-icon"><Footprints size={20} /></span>Az yürüyelim
              </button>
            </div>
            <button className="btn btn-secondary btn-block" disabled={busy} onClick={() => gate() && setAddOpen(true)}>
              <Plus size={18} /> Durak veya ilgi alanı ekle
            </button>
          </section>
        )}

        <section className="section">
          <h2 className="t-headline">Günün akışı</h2>
          <ol className="timeline">
            {route.stops.map((stop, index) => (
              <StopItem key={stop.id} stop={stop} index={index} isNext={stop.id === nextStop?.id}
                        editable={!finished && stop.status === 'PLANNED'} busy={busy}
                        onVisited={() => setStopStatus(stop, 'VISITED')}
                        onMore={() => setMenuStop(stop)} />
            ))}
          </ol>
        </section>

        <button className="btn btn-ghost btn-block" style={{ color: 'var(--danger)' }} onClick={() => setDeleteOpen(true)}>
          <Trash2 size={18} /> Rotayı sil
        </button>
      </div>

      <Sheet open={addOpen} onClose={() => setAddOpen(false)} label="Rotaya ekle">
        <AddSheet busy={busy} onPick={change => { setAddOpen(false); void replan(change) }} />
      </Sheet>

      <Sheet open={menuStop !== null} onClose={() => setMenuStop(null)} label={menuStop?.place.name ?? ''}>
        {menuStop && (
          <div className="list-group">
            <button className="list-item" disabled={busy} onClick={() => { const s = menuStop; setMenuStop(null); void replan({ type: 'REPLACE_STOP', stopId: s.id }) }}>
              <span className="list-item-icon"><Shuffle size={18} /></span>
              <span className="grow"><strong style={{ display: 'block' }}>Başka bir yerle değiştir</strong><span className="t-caption">Aynı saat için yakında başka bir {menuStop.typeLabel.toLocaleLowerCase('tr')} önerisi</span></span>
            </button>
            <button className="list-item" disabled={busy} onClick={() => { const s = menuStop; setMenuStop(null); void setStopStatus(s, 'SKIPPED') }}>
              <span className="list-item-icon"><SkipForward size={18} /></span>
              <span className="grow"><strong style={{ display: 'block' }}>Bu durağı atla</strong><span className="t-caption">Rotada kalır, gidilmedi olarak işaretlenir</span></span>
            </button>
            <button className="list-item list-item-danger" disabled={busy} onClick={() => { const s = menuStop; setMenuStop(null); void replan({ type: 'REMOVE_STOP', stopId: s.id }) }}>
              <span className="list-item-icon"><Trash2 size={18} /></span>
              <span className="grow"><strong style={{ display: 'block' }}>Rotadan çıkar</strong><span className="t-caption">Kalan duraklar yeniden zamanlanır</span></span>
            </button>
          </div>
        )}
      </Sheet>

      <Sheet open={deleteOpen} onClose={() => setDeleteOpen(false)} label="Rota silinsin mi?">
        <div className="stack">
          <p className="ink-2">Bu rota ve durakları kalıcı olarak silinir.</p>
          <div className="row">
            <button className="btn btn-secondary grow" onClick={() => setDeleteOpen(false)}>Vazgeç</button>
            <button className="btn btn-danger grow" onClick={() => void remove()}>Sil</button>
          </div>
        </div>
      </Sheet>
    </main>
  )
}

function StopItem({ stop, index, isNext, editable, busy, onVisited, onMore }: {
  stop: RouteStop
  index: number
  isNext: boolean
  editable: boolean
  busy: boolean
  onVisited: () => void
  onMore: () => void
}) {
  const Icon = STOP_ICON[stop.type]
  const stateClass = stop.status === 'VISITED' ? 'stop-done' : stop.status === 'SKIPPED' ? 'stop-skipped' : ''

  return (
    <>
      {stop.walkingMinutes > 0 && (
        <li className="walk" aria-hidden>
          <Footprints size={13} /> {stop.walkingMinutes} dk yürüme · {formatDistance(stop.distanceFromPreviousMeters)}
        </li>
      )}
      <li className={`stop ${stateClass}`}>
        <div className="stop-num">{stop.status === 'VISITED' ? <Check size={18} /> : index + 1}</div>
        <div className="card stop-card" style={isNext ? { borderColor: 'var(--brand)', boxShadow: '0 0 0 3px var(--brand-50)' } : undefined}>
          <div className="row-between">
            <span className="stop-time"><Icon size={14} /> {formatTime(stop.plannedStart)} – {formatTime(stop.plannedEnd)} · {stop.typeLabel.toLocaleUpperCase('tr')}</span>
            {isNext && <span className="badge badge-brand">Sıradaki</span>}
            {stop.status === 'VISITED' && <span className="badge badge-success">Gidildi</span>}
            {stop.status === 'SKIPPED' && <span className="badge">Atlandı</span>}
          </div>
          <Link to={`/places/${stop.place.id}`} className="stop-title">{stop.place.name}</Link>
          <div className="meta">
            {stop.place.rating != null && <span>★ {stop.place.rating.toFixed(1)}</span>}
            <span>{formatCost(stop.place.estimatedCost)}</span>
            {stop.place.neighborhood && <span>{stop.place.neighborhood}</span>}
            {stop.place.indoor && <span>Kapalı alan</span>}
          </div>
          <details>
            <summary>Neden burası? <ChevronDown size={14} /></summary>
            <ul className="reasons">{stop.reasons.map(r => <li key={r}><Check size={14} />{r}</li>)}</ul>
          </details>
          {editable && (
            <div className="stop-actions">
              <button className="btn btn-sm btn-tonal grow" disabled={busy} onClick={onVisited}><Check size={16} /> Gittim</button>
              <button className="btn btn-sm btn-secondary" disabled={busy} onClick={onMore} aria-label="Diğer seçenekler"><Ellipsis size={18} /> Seçenekler</button>
            </div>
          )}
        </div>
      </li>
    </>
  )
}

function AddSheet({ busy, onPick }: { busy: boolean; onPick: (change: Change) => void }) {
  return (
    <div className="stack" style={{ gap: 20 }}>
      <div className="field">
        <span className="field-label">Durak ekle</span>
        <div className="option-grid">
          {(Object.keys(STOP_TYPE_LABELS) as StopType[]).map(s => {
            const Icon = STOP_ICON[s]
            return (
              <button key={s} className="option" disabled={busy} onClick={() => onPick({ type: 'ADD_STOP', stopType: s })}>
                <Icon size={20} /> {STOP_TYPE_LABELS[s]}
              </button>
            )
          })}
        </div>
      </div>
      <div className="field">
        <span className="field-label">Daha fazlası olsun</span>
        <div className="chips">
          {Object.entries(INTEREST_LABELS).map(([key, label]) => (
            <button key={key} className="chip" disabled={busy} onClick={() => onPick({ type: 'ADD_INTEREST', interest: key })}>
              {label}
            </button>
          ))}
        </div>
      </div>
      <p className="t-caption">Değişiklikler bulunduğun konumdan itibaren planlanır.</p>
    </div>
  )
}
