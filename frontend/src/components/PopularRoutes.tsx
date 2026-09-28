import {
  ChevronDown, Coffee, Crown, Footprints, Landmark, MapPin, Play, Route as RouteIcon, Trees, UtensilsCrossed, Wallet,
  type LucideIcon,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { api } from '../api'
import type { PopularRoute, PopularRouteTheme } from '../api/types'
import { useAuth } from '../context/AuthContext'
import { popularRouteCost } from '../lib/format'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'
import { useGate } from './gate'
import { RouteMap } from './RouteMap'
import { Alert, EmptyState, ErrorState, ListSkeleton, Spinner } from './ui'
import { CategoryTile } from './visuals'

const THEME_ICON: Record<PopularRouteTheme, LucideIcon> = {
  HISTORY: Landmark,
  FOOD: UtensilsCrossed,
  COFFEE_DESSERT: Coffee,
  PARKS_VIEWS: Trees,
}

/** Ready-made themed routes of a city (or one of its districts); one card open at a time. */
export function PopularRoutes({ city, district }: { city: string; district: string | null }) {
  const t = useT()
  const { data, error, loading, reload } = useAsync(() => api.popularRoutes(city, district ?? undefined), [city, district])
  const [open, setOpen] = useState<PopularRouteTheme | null>(null)
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
        <PopularRouteCard key={route.theme} route={route} city={city} district={district}
                          expanded={open === route.theme}
                          onToggle={() => setOpen(current => (current === route.theme ? null : route.theme))} />
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
  const Icon = THEME_ICON[route.theme] ?? RouteIcon
  const stopCount = route.stops.length

  // Account + pass first (login or paywall, back here afterwards); 402 from the server opens the paywall too
  async function start() {
    if (!gate()) return
    setBusy(true)
    setError(null)
    try {
      const created = await api.startPopularRoute({ city, district: district ?? undefined, theme: route.theme })
      navigate(`/routes/${created.id}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Rota oluşturulamadı', 'Could not create the route'))
      setBusy(false)
    }
  }

  return (
    <article className="card popular-route">
      <button className="popular-route-head" onClick={onToggle} aria-expanded={expanded}>
        <span className={`popular-route-icon theme-${route.theme}`}><Icon size={20} /></span>
        <span className="grow stack-sm" style={{ gap: 2 }}>
          <span className="t-headline">{route.title}</span>
          <span className="t-caption">{route.description}</span>
        </span>
        <ChevronDown size={20} className={`popular-route-chevron ${expanded ? 'open' : ''}`} aria-hidden />
      </button>

      <div className="meta">
        <span><MapPin size={12} /> {stopCount} {t('durak', stopCount === 1 ? 'stop' : 'stops')}</span>
        <span><Footprints size={12} /> {route.totalWalkingMinutes} {t('dk yürüyüş', 'min walking')}</span>
        <span><Wallet size={12} /> {popularRouteCost(route.estimatedCostPerPerson, route.unknownPriceStops)}</span>
      </div>

      <ol className="mini-timeline" aria-label={t('Duraklar', 'Stops')}>
        {route.stops.map((stop, i) => (
          <li key={`${i}-${stop.place.id}`} className="mini-stop">
            {stop.place.image
              ? <CategoryTile category={stop.place.category} size={16} image={stop.place.image} alt={stop.place.name} className="mini-stop-photo" />
              : <span className="mini-stop-num" aria-hidden>{i + 1}</span>}
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
