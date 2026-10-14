import { ChevronRight, Heart, Landmark, MapPin } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'
import { PlaceRow } from './PlaceViews'
import { PopularRouteCard, PopularRoutes } from './PopularRoutes'
import { EmptyState, ErrorState, ListSkeleton } from './ui'
import { Rating } from './visuals'

// What Nomi users love, for all Türkiye (no city), a city, or only one of its districts (Keşfet's two boxes)

/**
 * "Popüler rotalar": routes users saved or liked (👎 counts against), most favourite first. Until an area has any,
 * the routes through its best-known sights are one tap away.
 */
export function FavoriteRoutes({ city, district, areaName }: { city: string | null; district: string | null; areaName: string }) {
  const t = useT()
  const routes = useAsync(() => api.communityRoutes(city ?? undefined, district ?? undefined), [city, district],
    { cacheKey: `community-routes:${city}:${district}` })
  const [open, setOpen] = useState<string | null>(null)
  const [famous, setFamous] = useState(false)
  useEffect(() => { setOpen(null); setFamous(false) }, [city, district])

  return (
    <section className="stack">
      <h2 className="t-headline"><Heart size={17} style={{ verticalAlign: -3 }} /> {t(`${areaName}: popüler rotalar`, `${areaName}: popular routes`)}</h2>
      {routes.loading ? <ListSkeleton rows={2} height={160} />
        : routes.error ? <ErrorState message={routes.error} onRetry={() => void routes.reload()} />
          : routes.data?.length ? routes.data.map(route => (
            <PopularRouteCard key={route.key} route={route} expanded={open === route.key}
                              onToggle={() => setOpen(current => (current === route.key ? null : route.key))}
                              create={() => api.startCommunityRoute({ city: city ?? undefined, district: district ?? undefined, key: route.key, date: route.date })} />
          )) : (
            <>
              <EmptyState icon={Heart} title={t('Henüz favori rota yok', 'No favourite routes yet')}
                          text={t('Bir rotayı kaydettiğinde ya da beğendiğinde burada görünür.', 'Routes show up here once people save or like them.')} />
              {/* The best-known sights need an area (a city at least) */}
              {city && !famous && (
                <button type="button" className="btn btn-secondary btn-block" onClick={() => setFamous(true)}>
                  <Landmark size={18} /> {t('Ünlü yerler rotalarına bak', 'See routes through famous sights')}
                </button>
              )}
              {city && famous && <PopularRoutes city={city} district={district} />}
            </>
          )}
    </section>
  )
}

/**
 * "Favori mekanlar": the places Nomi users rated with stars, best first (Bayesian, so one 5-star vote does not beat
 * twenty 4.8s). Saving a place is the user's own business and does not count here.
 */
export function FavoritePlaces({ city, district, areaName }: { city: string | null; district: string | null; areaName: string }) {
  const t = useT()
  const places = useAsync(() => api.favoritePlaces(city ?? undefined, district ?? undefined), [city, district],
    { cacheKey: `favorite-places:${city}:${district}` })

  return (
    <section className="stack">
      <div className="stack-sm">
        <h2 className="t-headline"><MapPin size={17} style={{ verticalAlign: -3 }} /> {t(`${areaName}: favori mekanlar`, `${areaName}: favourite places`)}</h2>
        <span className="t-caption">{t('Nomi kullanıcılarının yıldız puanına göre, en yüksekten.', 'By Nomi users’ star ratings, best first.')}</span>
      </div>
      {places.loading ? <ListSkeleton rows={3} height={96} />
        : places.error ? <ErrorState message={places.error} onRetry={() => void places.reload()} />
          : places.data?.length ? places.data.map(place => <PlaceRow key={place.id} place={place} showDistrict={!district} showVotes />)
            : <EmptyState icon={MapPin} title={t('Henüz puanlanan mekan yok', 'No rated places yet')}
                          text={t('Kullanıcılar mekanlara yıldız verdikçe en yüksek puanlılar burada sıralanır.', 'As people rate places, the best rated show up here.')} />}
    </section>
  )
}

/**
 * Keşfet's two boxes side by side above the place list: the top two popular routes and favourite places of the area.
 * The box title opens the full list.
 */
export function CommunityPreview({ city, district, onOpenRoutes, onOpenPlaces }: {
  city: string | null
  district: string | null
  onOpenRoutes: () => void
  onOpenPlaces: () => void
}) {
  const t = useT()
  // Same cache keys as the full lists: opening a box shows them at once
  const routes = useAsync(() => api.communityRoutes(city ?? undefined, district ?? undefined), [city, district],
    { cacheKey: `community-routes:${city}:${district}` })
  const places = useAsync(() => api.favoritePlaces(city ?? undefined, district ?? undefined), [city, district],
    { cacheKey: `favorite-places:${city}:${district}` })
  const topRoutes = Array.isArray(routes.data) ? routes.data.slice(0, 2) : []
  const topPlaces = Array.isArray(places.data) ? places.data.slice(0, 2) : []

  return (
    <div className="preview-boxes">
      <section className="card preview-box">
        <button type="button" className="preview-head" aria-label={t('Popüler rotalar', 'Popular routes')} aria-pressed={false} onClick={onOpenRoutes}>
          <span className="t-overline">{t('Popüler rotalar', 'Popular routes')}</span>
          <span className="preview-all">{t('Tümü', 'All')} <ChevronRight size={14} /></span>
        </button>
        {topRoutes.length ? topRoutes.map(r => (
          <button type="button" key={r.key} className="preview-item" onClick={onOpenRoutes}>
            <span className="preview-name">{r.title}</span>
            <span className="t-caption">{t(`${r.stops.length} durak`, `${r.stops.length} stops`)}</span>
          </button>
        )) : <span className="t-caption">{routes.loading ? '…' : t('Henüz yok', 'None yet')}</span>}
      </section>
      <section className="card preview-box">
        <button type="button" className="preview-head" aria-label={t('Favori mekanlar', 'Favourite places')} aria-pressed={false} onClick={onOpenPlaces}>
          <span className="t-overline">{t('Favori mekanlar', 'Favourite places')}</span>
          <span className="preview-all">{t('Tümü', 'All')} <ChevronRight size={14} /></span>
        </button>
        {topPlaces.length ? topPlaces.map(p => (
          <Link key={p.id} to={`/places/${p.id}`} className="preview-item">
            <span className="preview-name">{p.name}</span>
            <span className="t-caption row" style={{ gap: 4 }}>
              <Rating value={p.userRating ?? null} /> · {t(`${p.userRatingCount ?? 0} oy`, `${p.userRatingCount ?? 0} votes`)}
            </span>
          </Link>
        )) : <span className="t-caption">{places.loading ? '…' : t('Henüz yok', 'None yet')}</span>}
      </section>
    </div>
  )
}
