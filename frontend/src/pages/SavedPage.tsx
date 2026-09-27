import { Heart, Map as MapIcon } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { Locked } from '../components/gate'
import { PlaceRow } from '../components/PlaceViews'
import { RouteCard } from '../components/RouteCard'
import { EmptyState, ErrorState, ListSkeleton, Segmented } from '../components/ui'
import { useAuth } from '../context/AuthContext'
import { useSavedPlaces } from '../context/SavedPlacesContext'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'

export function SavedPage() {
  const { user } = useAuth()
  const t = useT()
  if (!user) {
    return (
      <main className="screen">
        <Locked icon={Heart} title={t('Beğendiklerin tek yerde', 'Your favourites in one place')}
                text={t('Gitmek istediğin mekanları ve sevdiğin rotaları kaydet, istediğin an geri dön.', 'Save the places you want to visit and the routes you love, and come back to them anytime.')} />
      </main>
    )
  }
  return <SavedLists />
}

function SavedLists() {
  const t = useT()
  const [tab, setTab] = useState<'places' | 'routes'>('places')
  const saved = useSavedPlaces()
  const routes = useAsync(() => api.routes(true), [])

  return (
    <main className="screen">
      <h1 className="t-display">{t('Kaydedilenler', 'Saved')}</h1>
      <Segmented value={tab} onChange={setTab} options={[
        { value: 'places', label: t(`Mekanlar · ${saved.places.length}`, `Places · ${saved.places.length}`) },
        { value: 'routes', label: t(`Rotalar · ${routes.data?.length ?? 0}`, `Routes · ${routes.data?.length ?? 0}`) },
      ]} />

      {tab === 'places' && (saved.places.length === 0 ? (
        <EmptyState icon={Heart} title={t('Henüz kaydettiğin mekan yok', 'No saved places yet')} text={t('Keşfet’te beğendiğin mekanların kalbine dokun.', 'Tap the heart on places you like in Explore.')}
                    action={<Link to="/explore" className="btn btn-primary">{t('Keşfet’e git', 'Go to Explore')}</Link>} />
      ) : (
        <div className="stack">{saved.places.map(p => <PlaceRow key={p.id} place={p} />)}</div>
      ))}

      {tab === 'routes' && (
        <>
          {routes.loading && <ListSkeleton rows={2} height={92} />}
          {routes.error && <ErrorState message={routes.error} onRetry={routes.reload} />}
          {routes.data?.length === 0 && (
            <EmptyState icon={MapIcon} title={t('Kaydedilen rota yok', 'No saved routes')} text={t('Bir rotanın detayında kalbe dokunarak kaydedebilirsin.', 'Tap the heart on a route to save it.')} />
          )}
          <div className="stack">{routes.data?.map(r => <RouteCard key={r.id} route={r} />)}</div>
        </>
      )}
    </main>
  )
}
