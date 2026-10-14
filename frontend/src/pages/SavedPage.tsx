import { Bookmark, Map as MapIcon } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { Locked } from '../components/gate'
import { PlaceRow } from '../components/PlaceViews'
import { RouteCard } from '../components/RouteCard'
import { EmptyState, ErrorState, ListSkeleton, Segmented } from '../components/ui'
import type { PlaceCategory } from '../api/types'
import { HScroll } from '../components/HScroll'
import { useAuth } from '../context/AuthContext'
import { useSavedPlaces } from '../context/SavedPlacesContext'
import { CATEGORY_LABELS } from '../lib/format'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'

export function SavedPage() {
  const { user } = useAuth()
  const t = useT()
  if (!user) {
    return (
      <main className="screen">
        <Locked icon={Bookmark} title={t('Beğendiklerin tek yerde', 'Your favourites in one place')}
                text={t('Gitmek istediğin mekanları ve sevdiğin rotaları kaydet, istediğin an geri dön.', 'Save the places you want to visit and the routes you love, and come back to them anytime.')} />
      </main>
    )
  }
  return <SavedLists />
}

function SavedLists() {
  const t = useT()
  const [tab, setTab] = useState<'places' | 'routes'>('places')
  const [category, setCategory] = useState<PlaceCategory | null>(null)
  const saved = useSavedPlaces()
  // Category chips with counts, only for the kinds the user saved ("Kafe (3)")
  const counts = new Map<PlaceCategory, number>()
  saved.places.forEach(p => counts.set(p.category, (counts.get(p.category) ?? 0) + 1))
  const shown = category ? saved.places.filter(p => p.category === category) : saved.places
  const routes = useAsync(() => api.routes(true), [], { cacheKey: 'routes:saved' })

  return (
    <main className="screen">
      <div className="stack-sm">
        <h1 className="t-display">{t('Kaydedilenler', 'Saved')}</h1>
        <p className="t-caption">{t('Kişisel gezi defterin: gitmek istediğin mekanlar ve sevdiğin rotalar', 'Your travel notebook: places you want to visit and routes you love')}</p>
      </div>
      <Segmented value={tab} onChange={setTab} options={[
        { value: 'places', label: t(`Mekanlar · ${saved.places.length}`, `Places · ${saved.places.length}`) },
        { value: 'routes', label: t(`Rotalar · ${routes.data?.length ?? 0}`, `Routes · ${routes.data?.length ?? 0}`) },
      ]} />

      {tab === 'places' && (saved.places.length === 0 ? (
        <EmptyState icon={Bookmark} title={t('Henüz kaydettiğin mekan yok', 'No saved places yet')} text={t('Keşfet’te beğendiğin mekanlardaki kaydet simgesine dokun.', 'Tap the save icon on places you like in Explore.')}
                    action={<Link to="/explore" className="btn btn-primary">{t('Keşfet’e git', 'Go to Explore')}</Link>} />
      ) : (
        <>
          {counts.size > 1 && (
            <HScroll className="h-scroll chip-scroll" style={{ gap: 8 }} label={t('Kategoriler', 'Categories')}>
              <button type="button" className={`chip ${category === null ? 'active' : ''}`} aria-pressed={category === null}
                      onClick={() => setCategory(null)}>{t('Tümü', 'All')} ({saved.places.length})</button>
              {[...counts].map(([c, n]) => (
                <button type="button" key={c} className={`chip ${category === c ? 'active' : ''}`} aria-pressed={category === c}
                        onClick={() => setCategory(category === c ? null : c)}>{CATEGORY_LABELS[c]} ({n})</button>
              ))}
            </HScroll>
          )}
          <div className="stack">{shown.map(p => <PlaceRow key={p.id} place={p} showDistrict />)}</div>
        </>
      ))}

      {tab === 'routes' && (
        <>
          {routes.loading && <ListSkeleton rows={2} height={92} />}
          {routes.error && <ErrorState message={routes.error} onRetry={routes.reload} />}
          {routes.data?.length === 0 && (
            <EmptyState icon={MapIcon} title={t('Kaydedilen rota yok', 'No saved routes')} text={t('Bir rotanın detayında kaydet simgesine dokunarak kaydedebilirsin.', 'Tap the save icon on a route to save it.')} />
          )}
          <div className="stack">{routes.data?.map(r => <RouteCard key={r.id} route={r} />)}</div>
        </>
      )}
    </main>
  )
}
