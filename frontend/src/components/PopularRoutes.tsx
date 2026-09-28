import { ChevronDown, Crown, Footprints, MapPin, Play, Route as RouteIcon, TrendingUp, Wallet } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { api } from '../api'
import type { PlaceCategory, PopularRoute, PopularRouteStop, StopType } from '../api/types'
import { useAuth } from '../context/AuthContext'
import { popularRouteCost } from '../lib/format'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'
import { useGate } from './gate'
import { RouteMap } from './RouteMap'
import { Alert, EmptyState, ErrorState, ListSkeleton, Spinner } from './ui'
import { STOP_ICON, usePlacePhoto } from './visuals'

// Tile colour of a stop without a photo: the colour of the kind of place that fills it
const STOP_TILE: Record<StopType, PlaceCategory> = {
  BREAKFAST: 'BREAKFAST',
  SIGHTSEEING: 'ATTRACTION',
  LUNCH: 'RESTAURANT',
  COFFEE: 'CAFE',
  DESSERT: 'DESSERT',
  DINNER: 'RESTAURANT',
}

/**
 * The most popular sights of a city (or one of its districts) that lie close together, in walking order, with
 * meals and coffee in between; one card open at a time.
 */
export function PopularRoutes({ city, district }: { city: string; district: string | null }) {
  const t = useT()
  const { data, error, loading, reload } = useAsync(() => api.popularRoutes(city, district ?? undefined), [city, district])
  const [open, setOpen] = useState<string | null>(null)
  useEffect(() => setOpen(null), [city, district])

  if (loading) return <ListSkeleton rows={3} height={180} />
  if (error) return <ErrorState message={error} onRetry={() => void reload()} />
  if (!data?.length) {
    return (
      <EmptyState icon={RouteIcon} title={t('Bu bölge için henüz yeterli mekan yok', 'Not enough places in this area yet')}
                  text={t('Başka bir ilçe ya da şehir seçmeyi dene.', 'Try another district or city.')} />
    )
  }
  return (
    <div className="stack">
      {data.map(route => (
        <PopularRouteCard key={route.key} route={route} city={city} district={district}
                          expanded={open === route.key}
                          onToggle={() => setOpen(current => (current === route.key ? null : route.key))} />
      ))}
    </div>
  )
}

function PopularRouteCard({ route, city, district, expanded, onToggle }: {
  route: PopularRoute
  city: string
  district: string | null
  expanded: boolean
  onToggle: () => void
}) {
  const t = useT()
  const gate = useGate()
  const navigate = useNavigate()
  const { hasAccess } = useAuth()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const stopCount = route.stops.length

  // Account + pass first (login or paywall, back here afterwards); 402 from the server opens the paywall too
  async function start() {
    if (!gate()) return
    setBusy(true)
    setError(null)
    try {
      const created = await api.startPopularRoute({ city, district: district ?? undefined, key: route.key, date: route.date })
      navigate(`/routes/${created.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Rota oluşturulamadı', 'Could not create the route'))
      setBusy(false)
    }
  }

  return (
    <article className="card popular-route">
      <button className="popular-route-head" onClick={onToggle} aria-expanded={expanded}>
        <span className="popular-route-icon"><RouteIcon size={20} /></span>
        <span className="grow stack-sm" style={{ gap: 2 }}>
          <span className="t-headline">{route.title}</span>
          <span className="t-caption">{route.description}</span>
        </span>
        <ChevronDown size={20} className={`popular-route-chevron ${expanded ? 'open' : ''}`} aria-hidden />
      </button>

      <span className="popular-route-note"><TrendingUp size={13} aria-hidden /> {route.popularityNote}</span>

      <div className="meta">
        <span><MapPin size={12} /> {stopCount} {t('durak', stopCount === 1 ? 'stop' : 'stops')}</span>
        <span><Footprints size={12} /> {route.totalWalkingMinutes} {t('dk yürüyüş', 'min walking')}</span>
        <span><Wallet size={12} /> {popularRouteCost(route.estimatedCostPerPerson, route.unknownPriceStops)}</span>
      </div>

      <ol className="mini-timeline" aria-label={t('Duraklar', 'Stops')}>
        {route.stops.map((stop, i) => (
          <li key={`${i}-${stop.place.id}`} className="mini-stop">
            <StopTile stop={stop} />
            <span className="grow stack-sm" style={{ gap: 0, minWidth: 0 }}>
              <span className="stop-time">{stop.time} · {stop.typeLabel}</span>
              <Link to={`/places/${stop.place.id}`} className="mini-stop-name">{stop.place.name}</Link>
            </span>
          </li>
        ))}
      </ol>

      {expanded && (
        <div className="stack">
          <RouteMap height={220}
                    start={{ latitude: route.startLatitude, longitude: route.startLongitude }}
                    points={route.stops.map((stop, i) => ({
                      latitude: stop.place.latitude, longitude: stop.place.longitude, label: String(i + 1), title: stop.place.name,
                    }))} />
          <span className="t-caption"><MapPin size={12} style={{ verticalAlign: -1 }} /> {t('Başlangıç', 'Start')}: {route.startLabel}</span>
          {error && <Alert tone="danger"><span>{error}</span></Alert>}
          <button className="btn btn-primary btn-block" onClick={() => void start()} disabled={busy}>
            {busy
              ? <><Spinner /> {t('Rotan hazırlanıyor', 'Preparing your route')}</>
              : <><Play size={18} /> {t('Bu rotayı başlat', 'Start this route')}</>}
          </button>
          {!hasAccess && (
            <span className="t-caption" style={{ textAlign: 'center' }}>
              <Crown size={12} style={{ verticalAlign: -1 }} /> {t('Rotayı başlatmak Nomi Premium ile', 'Starting a route needs Nomi Premium')}
            </span>
          )}
        </div>
      )}
    </article>
  )
}

// The stop's kind as an icon (sight, lunch, coffee ...), covered by the place photo when there is one
function StopTile({ stop }: { stop: PopularRouteStop }) {
  const Icon = STOP_ICON[stop.type]
  const photo = usePlacePhoto(stop.place.image)
  return (
    <span className={`tile tile-${STOP_TILE[stop.type]} mini-stop-photo`} data-stop-type={stop.type}
          aria-hidden={photo.url ? undefined : true}>
      <Icon size={16} strokeWidth={1.8} style={{ position: 'relative', zIndex: 1 }} />
      {photo.url && (
        <img className="tile-photo" src={photo.url} alt={stop.place.name} loading="lazy" decoding="async" onError={photo.onError} />
      )}
    </span>
  )
}
