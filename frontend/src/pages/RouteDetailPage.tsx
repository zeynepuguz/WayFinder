import {
  BatteryLow, Check, MapPin, Sparkles, ChevronDown, CloudRain, Footprints, Heart, Plus, RefreshCw, Shuffle, Trash2, TriangleAlert, Wallet,
  Clock, Ellipsis, History, SkipForward, X,
} from 'lucide-react'
import { useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { api } from '../api'
import type { ReplanRequest, Route, RouteStop, StopStatus, StopType } from '../api/types'
import { useGate } from '../components/gate'
import { RouteMap } from '../components/RouteMap'
import { Alert, BackButton, ConfirmSheet, ErrorState, Sheet, Skeleton, Spinner, useToast } from '../components/ui'
import { STOP_ICON, WEATHER_ICON } from '../components/visuals'
import { useUserLocation } from '../context/LocationContext'
import { errorMessage, formatCost, formatDate, formatDistance, formatTime, INTEREST_LABELS, isPastRoute, routeTotalCost, STOP_TYPE_LABELS, TAG_LABELS } from '../lib/format'
import { locale, useT, type Translate } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'

type Change = Omit<ReplanRequest, 'latitude' | 'longitude'>

// Ready answers for "Neye göre değiştirelim?" by stop type (the backend reads any text: planning/StopWish)
function swapWishes(type: StopType, t: Translate): string[] {
  const common = [t('Daha ucuz', 'Cheaper'), t('Daha yakın', 'Closer'), t('Kapalı alan', 'Indoors')]
  if (type === 'LUNCH' || type === 'DINNER') return [...common, t('Kebap', 'Kebab'), t('Balık', 'Fish'), t('Ev yemekleri', 'Home cooking')]
  if (type === 'SIGHTSEEING') return [t('Daha yakın', 'Closer'), t('Müze', 'Museum'), t('Tarihi yer', 'Historic'), t('Manzaralı', 'With a view'), t('Kapalı alan', 'Indoors')]
  if (type === 'COFFEE' || type === 'BREAKFAST') return [...common, t('Bahçeli', 'With a garden'), t('Deniz kenarı', 'By the sea')]
  return common
}

export function RouteDetailPage() {
  const { id } = useParams()
  const routeId = Number(id)
  const navigate = useNavigate()
  const location = useUserLocation()
  const gate = useGate()
  const toast = useToast()
  const t = useT()
  // Interests chosen in the new-route form (shown when the server's route does not list them)
  const requested = (useLocation().state as { interests?: string[] } | null)?.interests

  const { data: route, setData: setRoute, error, loading, reload } = useAsync(() => api.route(routeId), [routeId])
  const [busy, setBusy] = useState(false)
  const [changes, setChanges] = useState<string[]>([])
  const [actionError, setActionError] = useState<string | null>(null)
  const [addOpen, setAddOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [menuStop, setMenuStop] = useState<RouteStop | null>(null)
  // "Başka bir yerle değiştir": the stop being swapped and what the new place should be like
  const [swapStop, setSwapStop] = useState<RouteStop | null>(null)
  const [wish, setWish] = useState('')

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
      setActionError(errorMessage(e, t('İşlem başarısız', 'Something went wrong')))
    } finally {
      setBusy(false)
    }
  }

  const replan = (change: Change) =>
    act(() => api.replan(routeId, { ...change, latitude: location.latitude, longitude: location.longitude }))
  const setStopStatus = (stop: RouteStop, status: StopStatus) => act(() => api.updateStop(routeId, stop.id, status))

  async function remove() {
    setDeleting(true)
    setDeleteError(null)
    try {
      await api.deleteRoute(routeId)
      toast(t('Rota silindi', 'Route deleted'))
      navigate('/routes', { replace: true })
    } catch (e) {
      setDeleteError(errorMessage(e, t('Rota silinemedi', 'Couldn’t delete the route')))
    } finally {
      setDeleting(false)
    }
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
    return <main className="screen"><BackButton to="/routes" /><ErrorState message={error ?? t('Rota bulunamadı', 'Route not found')} onRetry={reload} /></main>
  }

  // A route of a day that is over is read-only: no replans, no stop changes (the server answers 409)
  const past = isPastRoute(route)
  const finished = route.status === 'COMPLETED' || past
  const WeatherIcon = WEATHER_ICON[route.weather.condition ?? ''] ?? CloudRain
  const nextStop = route.stops.find(s => s.status === 'PLANNED')
  const interests = route.interests?.length ? route.interests : requested ?? []

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
                  aria-label={route.saved ? t('Kaydedilenlerden çıkar', 'Remove from saved') : t('Rotayı kaydet', 'Save route')}
                  onClick={() => act(() => api.updateRoute(routeId, { saved: !route.saved }))}>
            <Heart size={20} fill={route.saved ? '#ff5a36' : 'none'} color={route.saved ? '#ff5a36' : 'currentColor'} />
          </button>
        </div>
      </div>

      <div className="place-sheet pad">
        <div className="stack-sm">
          <span className="t-overline">{formatDate(route.date)} · {t(`${route.partySize} kişi`, route.partySize === 1 ? '1 person' : `${route.partySize} people`)}</span>
          <h1 className="t-title">{route.title}</h1>
          {route.startLabel && (
            <span className="t-caption row" style={{ gap: 4 }}><MapPin size={14} /> {t('Başlangıç', 'Start')}: {route.startLabel}</span>
          )}
          {interests.length > 0 && (
            <div className="route-interests" aria-label={t('İlgi alanların', 'Your interests')}>
              <Sparkles size={14} className="muted" style={{ alignSelf: 'center' }} />
              {interests.map(key => <span key={key} className="badge badge-brand">{TAG_LABELS[key] ?? key}</span>)}
            </div>
          )}
        </div>

        <div className="stats">
          <div className="stat"><Wallet size={16} className="muted" /><strong>{routeTotalCost(route)}</strong>
            <span>{route.budget != null ? t(`bütçe ₺${route.budget.toLocaleString(locale())}`, `budget ₺${route.budget.toLocaleString(locale())}`) : t('tahmini', 'estimated')}</span></div>
          <div className="stat"><Footprints size={16} className="muted" /><strong>~{route.totalWalkingMinutes} {t('dk', 'min')}</strong><span>{t('yürüme', 'walking')}</span></div>
          <div className="stat"><Clock size={16} className="muted" /><strong>{route.stops.length}</strong><span>{t('durak', route.stops.length === 1 ? 'stop' : 'stops')}</span></div>
        </div>

        {changes.length > 0 && (
          <Alert tone="success" icon={RefreshCw} action={
            <button className="icon-btn icon-btn-plain" style={{ width: 28, height: 28 }} aria-label={t('Kapat', 'Close')} onClick={() => setChanges([])}><X size={16} /></button>
          }>
            <strong>{t('Rotan güncellendi', 'Your route has been updated')}</strong>
            <ul>{changes.map(c => <li key={c}>{c}</li>)}</ul>
          </Alert>
        )}
        {actionError && <Alert tone="danger"><span>{actionError}</span></Alert>}
        {past && (
          <Alert tone="info" icon={History}>
            <span>{t('Bu rota geçmiş bir güne ait; artık değiştirilemez. Yeni bir rota oluşturabilir ya da asistana yazabilirsin.',
              'This route belongs to a day that has passed and can no longer be changed. You can create a new route or ask the assistant.')}</span>
          </Alert>
        )}

        {route.weather.advice && (
          <Alert tone="info" icon={WeatherIcon}><span>{route.weather.advice}</span></Alert>
        )}
        {route.notes.map(note => <Alert key={note} tone="warning" icon={TriangleAlert}><span>{note}</span></Alert>)}

        {!finished && (
          <section className="section">
            <div className="row-between">
              <h2 className="t-headline">{t('Planı değiştir', 'Change the plan')}</h2>
              {busy && <Spinner />}
            </div>
            <div className="quick-actions">
              <button className="quick-action" disabled={busy} onClick={() => replan({ type: 'TIRED' })}>
                <span className="qa-icon"><BatteryLow size={20} /></span>{t('Yorulduk', 'We’re tired')}
              </button>
              <button className="quick-action" disabled={busy} onClick={() => replan({ type: 'WEATHER_CHANGED' })}>
                <span className="qa-icon"><CloudRain size={20} /></span>{t('Yağmur başladı', 'It’s raining')}
              </button>
              <button className="quick-action" disabled={busy} onClick={() => replan({ type: 'LESS_WALKING' })}>
                <span className="qa-icon"><Footprints size={20} /></span>{t('Az yürüyelim', 'Less walking')}
              </button>
            </div>
            <button className="btn btn-secondary btn-block" disabled={busy} onClick={() => gate() && setAddOpen(true)}>
              <Plus size={18} /> {t('Durak veya ilgi alanı ekle', 'Add a stop or interest')}
            </button>
          </section>
        )}

        <section className="section">
          <h2 className="t-headline">{t('Günün akışı', 'Your day')}</h2>
          <ol className="timeline">
            {route.stops.map((stop, index) => (
              <StopItem key={stop.id} stop={stop} index={index} isNext={stop.id === nextStop?.id}
                        editable={!finished && stop.status === 'PLANNED'} busy={busy}
                        onVisited={() => setStopStatus(stop, 'VISITED')}
                        onMore={() => setMenuStop(stop)} />
            ))}
          </ol>
        </section>

        <button className="btn btn-ghost btn-block" style={{ color: 'var(--danger)' }} onClick={() => { setDeleteError(null); setDeleteOpen(true) }}>
          <Trash2 size={18} /> {t('Rotayı sil', 'Delete route')}
        </button>
      </div>

      <Sheet open={addOpen} onClose={() => setAddOpen(false)} label={t('Rotaya ekle', 'Add to route')}>
        <AddSheet busy={busy} onPick={change => { setAddOpen(false); void replan(change) }} />
      </Sheet>

      <Sheet open={swapStop !== null} onClose={() => setSwapStop(null)} label={t('Neye göre değiştirelim?', 'What should the new place be like?')}>
        {swapStop && (
          <form className="stack" onSubmit={e => { e.preventDefault(); const s = swapStop; setSwapStop(null); void replan({ type: 'REPLACE_STOP', stopId: s.id, wish: wish.trim() || undefined }) }}>
            <p className="t-caption">{t(`${swapStop.place.name} yerine nasıl bir yer istersin? Yazabilir ya da bir seçenek seçebilirsin.`, `What would you like instead of ${swapStop.place.name}? Type it or pick an option.`)}</p>
            <span className="input">
              <input value={wish} onChange={e => setWish(e.target.value)} maxLength={200} autoFocus
                aria-label={t('Nasıl bir yer', 'What kind of place')}
                placeholder={swapStop.type === 'LUNCH' || swapStop.type === 'DINNER' ? t('Örn. kebap, balık, daha ucuz', 'E.g. kebab, fish, cheaper') : t('Örn. daha yakın, kapalı alan, manzaralı', 'E.g. closer, indoors, with a view')} />
            </span>
            <div className="chips">
              {swapWishes(swapStop.type, t).map(w => (
                <button key={w} type="button" className={`chip ${wish === w ? 'active' : ''}`} aria-pressed={wish === w} onClick={() => setWish(w)}>{w}</button>
              ))}
            </div>
            <button type="submit" className="btn btn-primary btn-block" disabled={busy || !wish.trim()}>{t('Buna göre değiştir', 'Swap with this')}</button>
            <button type="button" className="btn btn-ghost btn-block" disabled={busy} onClick={() => { const s = swapStop; setSwapStop(null); void replan({ type: 'REPLACE_STOP', stopId: s.id }) }}>{t('Fark etmez, başka bir yer öner', 'Anything else is fine')}</button>
          </form>
        )}
      </Sheet>

      <Sheet open={menuStop !== null} onClose={() => setMenuStop(null)} label={menuStop?.place.name ?? ''}>
        {menuStop && (
          <div className="list-group">
            <button className="list-item" disabled={busy} onClick={() => { const s = menuStop; setMenuStop(null); setWish(''); setSwapStop(s) }}>
              <span className="list-item-icon"><Shuffle size={18} /></span>
              <span className="grow"><strong style={{ display: 'block' }}>{t('Başka bir yerle değiştir', 'Swap for another place')}</strong><span className="t-caption">{t(`Aynı saat için yakında başka bir ${menuStop.typeLabel.toLocaleLowerCase('tr')} önerisi`, `Another ${menuStop.typeLabel.toLocaleLowerCase(locale())} nearby at the same time`)}</span></span>
            </button>
            <button className="list-item" disabled={busy} onClick={() => { const s = menuStop; setMenuStop(null); void setStopStatus(s, 'SKIPPED') }}>
              <span className="list-item-icon"><SkipForward size={18} /></span>
              <span className="grow"><strong style={{ display: 'block' }}>{t('Bu durağı atla', 'Skip this stop')}</strong><span className="t-caption">{t('Rotada kalır, gidilmedi olarak işaretlenir', 'Stays on the route, marked as not visited')}</span></span>
            </button>
            <button className="list-item list-item-danger" disabled={busy} onClick={() => { const s = menuStop; setMenuStop(null); void replan({ type: 'REMOVE_STOP', stopId: s.id }) }}>
              <span className="list-item-icon"><Trash2 size={18} /></span>
              <span className="grow"><strong style={{ display: 'block' }}>{t('Rotadan çıkar', 'Remove from route')}</strong><span className="t-caption">{t('Kalan duraklar yeniden zamanlanır', 'The remaining stops are rescheduled')}</span></span>
            </button>
          </div>
        )}
      </Sheet>

      <ConfirmSheet open={deleteOpen} onClose={() => setDeleteOpen(false)} label={t('Rota silinsin mi?', 'Delete this route?')}
                    text={t('Bu rota ve durakları kalıcı olarak silinir.', 'This route and its stops will be permanently deleted.')}
                    confirmLabel={t('Sil', 'Delete')} busy={deleting} error={deleteError} onConfirm={() => void remove()} />
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
  const t = useT()
  const Icon = STOP_ICON[stop.type]
  const stateClass = stop.status === 'VISITED' ? 'stop-done' : stop.status === 'SKIPPED' ? 'stop-skipped' : ''

  return (
    <>
      {stop.walkingMinutes > 0 && (
        <li className="walk" aria-hidden>
          <Footprints size={13} /> {t(`${stop.walkingMinutes} dk yürüme`, `${stop.walkingMinutes} min walk`)} · {formatDistance(stop.distanceFromPreviousMeters)}
        </li>
      )}
      <li className={`stop ${stateClass}`}>
        <div className="stop-num">{stop.status === 'VISITED' ? <Check size={18} /> : index + 1}</div>
        <div className="card stop-card" style={isNext ? { borderColor: 'var(--brand)', boxShadow: '0 0 0 3px var(--brand-50)' } : undefined}>
          <div className="row-between">
            <span className="stop-time"><Icon size={14} /> {formatTime(stop.plannedStart)} – {formatTime(stop.plannedEnd)} · {stop.typeLabel.toLocaleUpperCase(locale())}</span>
            {isNext && <span className="badge badge-brand">{t('Sıradaki', 'Next')}</span>}
            {stop.status === 'VISITED' && <span className="badge badge-success">{t('Gidildi', 'Visited')}</span>}
            {stop.status === 'SKIPPED' && <span className="badge">{t('Atlandı', 'Skipped')}</span>}
          </div>
          <Link to={`/places/${stop.place.id}`} className="stop-title">{stop.place.name}</Link>
          <div className="meta">
            {stop.place.rating != null && <span>★ {stop.place.rating.toFixed(1)}</span>}
            <span>{formatCost(stop.place.estimatedCost)}</span>
            {stop.place.neighborhood && <span>{stop.place.neighborhood}</span>}
            {stop.place.indoor && <span>{t('Kapalı alan', 'Indoor')}</span>}
          </div>
          <details>
            <summary>{t('Neden burası?', 'Why here?')} <ChevronDown size={14} /></summary>
            <ul className="reasons">{stop.reasons.map(r => <li key={r}><Check size={14} />{r}</li>)}</ul>
          </details>
          {editable && (
            <div className="stop-actions">
              <button className="btn btn-sm btn-tonal grow" disabled={busy} onClick={onVisited}><Check size={16} /> {t('Gittim', 'Been there')}</button>
              <button className="btn btn-sm btn-secondary" disabled={busy} onClick={onMore} aria-label={t('Diğer seçenekler', 'More options')}><Ellipsis size={18} /> {t('Seçenekler', 'Options')}</button>
            </div>
          )}
        </div>
      </li>
    </>
  )
}

function AddSheet({ busy, onPick }: { busy: boolean; onPick: (change: Change) => void }) {
  const t = useT()
  return (
    <div className="stack" style={{ gap: 20 }}>
      <div className="field">
        <span className="field-label">{t('Durak ekle', 'Add a stop')}</span>
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
        <span className="field-label">{t('Daha fazlası olsun', 'More of this')}</span>
        <div className="chips">
          {Object.entries(INTEREST_LABELS).map(([key, label]) => (
            <button key={key} className="chip" disabled={busy} onClick={() => onPick({ type: 'ADD_INTEREST', interest: key })}>
              {label}
            </button>
          ))}
        </div>
      </div>
      <p className="t-caption">{t('Değişiklikler bulunduğun konumdan itibaren planlanır.', 'Changes are planned from where you are now.')}</p>
    </div>
  )
}
