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
import { useAsync } from '../lib/useAsync'

export function SavedPage() {
  const { user } = useAuth()
  if (!user) {
    return (
      <main className="screen">
        <Locked icon={Heart} title="Beğendiklerin tek yerde"
                text="Gitmek istediğin mekanları ve sevdiğin rotaları kaydet, istediğin an geri dön." />
      </main>
    )
  }
  return <SavedLists />
}

function SavedLists() {
  const [tab, setTab] = useState<'places' | 'routes'>('places')
  const saved = useSavedPlaces()
  const routes = useAsync(() => api.routes(true), [])

  return (
    <main className="screen">
      <h1 className="t-display">Kaydedilenler</h1>
      <Segmented value={tab} onChange={setTab} options={[
        { value: 'places', label: `Mekanlar · ${saved.places.length}` },
        { value: 'routes', label: `Rotalar · ${routes.data?.length ?? 0}` },
      ]} />

      {tab === 'places' && (saved.places.length === 0 ? (
        <EmptyState icon={Heart} title="Henüz kaydettiğin mekan yok" text="Keşfet’te beğendiğin mekanların kalbine dokun."
                    action={<Link to="/explore" className="btn btn-primary">Keşfet’e git</Link>} />
      ) : (
        <div className="stack">{saved.places.map(p => <PlaceRow key={p.id} place={p} />)}</div>
      ))}

      {tab === 'routes' && (
        <>
          {routes.loading && <ListSkeleton rows={2} height={92} />}
          {routes.error && <ErrorState message={routes.error} onRetry={routes.reload} />}
          {routes.data?.length === 0 && (
            <EmptyState icon={MapIcon} title="Kaydedilen rota yok" text="Bir rotanın detayında kalbe dokunarak kaydedebilirsin." />
          )}
          <div className="stack">{routes.data?.map(r => <RouteCard key={r.id} route={r} />)}</div>
        </>
      )}
    </main>
  )
}
