import { Heart, Landmark, MapPin } from 'lucide-react'
import { useEffect, useState } from 'react'
import { api } from '../api'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'
import { PlaceRow } from './PlaceViews'
import { PopularRouteCard, PopularRoutes } from './PopularRoutes'
import { EmptyState, ErrorState, ListSkeleton } from './ui'

/**
 * Keşfet > Popüler: what Nomi users love, for all Türkiye (no city), a city, or only one of its districts.
 * Favourite routes are the routes users saved or liked (👎 counts against); favourite places the places they saved
 * or rated well. Until an area has favourite routes, the routes through its best-known sights are one tap away.
 */
export function Favorites({ city, district, areaName }: { city: string | null; district: string | null; areaName: string }) {
  const t = useT()
  const routes = useAsync(() => api.communityRoutes(city ?? undefined, district ?? undefined), [city, district])
  const places = useAsync(() => api.favoritePlaces(city ?? undefined, district ?? undefined), [city, district])
  const [open, setOpen] = useState<string | null>(null)
  const [famous, setFamous] = useState(false)
  useEffect(() => { setOpen(null); setFamous(false) }, [city, district])

  return (
    <div className="stack" style={{ gap: 24 }}>
      <section className="stack">
        <h2 className="t-headline"><Heart size={17} style={{ verticalAlign: -3 }} /> {t(`${areaName}: favori rotalar`, `${areaName}: favourite routes`)}</h2>
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

      <section className="stack">
        <h2 className="t-headline"><MapPin size={17} style={{ verticalAlign: -3 }} /> {t(`${areaName}: favori mekanlar`, `${areaName}: favourite places`)}</h2>
        {places.loading ? <ListSkeleton rows={3} height={96} />
          : places.error ? <ErrorState message={places.error} onRetry={() => void places.reload()} />
            : places.data?.length ? places.data.map(place => <PlaceRow key={place.id} place={place} showDistrict={!district} />)
              : <EmptyState icon={MapPin} title={t('Henüz favori mekan yok', 'No favourite places yet')}
                            text={t('Kullanıcıların kaydettiği ya da yüksek puan verdiği mekanlar burada görünür.', 'Places people save or rate highly show up here.')} />}
      </section>
    </div>
  )
}
