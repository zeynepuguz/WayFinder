import { List, Map as MapIcon, Search, SearchX, SlidersHorizontal, Umbrella } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { api } from '../api'
import type { Place, PlaceCategory } from '../api/types'
import { PlaceRow } from '../components/PlaceViews'
import { RouteMap } from '../components/RouteMap'
import { EmptyState, ErrorState, ListSkeleton, Segmented, Sheet } from '../components/ui'
import { CATEGORY_ICON } from '../components/visuals'
import { useUserLocation } from '../context/LocationContext'
import { CATEGORY_LABELS } from '../lib/format'

type Mode = 'nearby' | 'all'
const PRICE_OPTIONS = [
  { value: '', label: 'Hepsi' },
  { value: '0', label: 'Ücretsiz' },
  { value: '200', label: '≤ 200 TL' },
  { value: '400', label: '≤ 400 TL' },
  { value: '700', label: '≤ 700 TL' },
]

export function ExplorePage() {
  const location = useUserLocation()
  const [params, setParams] = useSearchParams()
  const category = (params.get('category') as PlaceCategory | null) ?? null

  const [mode, setMode] = useState<Mode>('nearby')
  const [query, setQuery] = useState('')
  const [indoorOnly, setIndoorOnly] = useState(false)
  const [maxCost, setMaxCost] = useState('')
  const [view, setView] = useState<'list' | 'map'>('list')
  const [filtersOpen, setFiltersOpen] = useState(false)

  const [places, setPlaces] = useState<Place[]>([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const setCategory = (c: PlaceCategory | null) => setParams(c ? { category: c } : {}, { replace: true })
  const activeFilters = (indoorOnly ? 1 : 0) + (maxCost ? 1 : 0)

  useEffect(() => {
    const timer = setTimeout(() => void load(0), query ? 300 : 0)
    return () => clearTimeout(timer)
    // Reload from the first page whenever a filter changes (search is debounced)
  }, [mode, category, query, indoorOnly, maxCost, location.latitude, location.longitude])

  async function load(nextPage: number) {
    setLoading(true)
    setError(null)
    try {
      if (mode === 'nearby') {
        // Nearby list is sorted by PostGIS distance; filters are applied on that small result
        const nearby = await api.nearbyPlaces(location.latitude, location.longitude, 2500)
        const q = query.toLocaleLowerCase('tr')
        setPlaces(nearby.filter(p =>
          (!category || p.category === category)
          && (!indoorOnly || p.indoor)
          && (!maxCost || (p.estimatedCost ?? 0) <= Number(maxCost))
          && (!q || p.name.toLocaleLowerCase('tr').includes(q))))
        setHasMore(false)
      } else {
        const result = await api.searchPlaces({
          category: category ?? undefined,
          q: query || undefined,
          indoor: indoorOnly || undefined,
          maxCost: maxCost ? Number(maxCost) : undefined,
          page: nextPage,
          size: 20,
        })
        setPlaces(current => (nextPage === 0 ? result.content : [...current, ...result.content]))
        setHasMore(result.page + 1 < result.totalPages)
      }
      setPage(nextPage)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Mekanlar yüklenemedi')
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="screen">
      <div className="row-between">
        <h1 className="t-display">Keşfet</h1>
        <button className="icon-btn" aria-label={view === 'list' ? 'Harita görünümü' : 'Liste görünümü'}
                onClick={() => setView(v => (v === 'list' ? 'map' : 'list'))}>
          {view === 'list' ? <MapIcon size={20} /> : <List size={20} />}
        </button>
      </div>

      <div className="row">
        <label className="search grow">
          <Search size={18} />
          <input type="search" placeholder="Mekan, kafe, müze ara" value={query} onChange={e => setQuery(e.target.value)}
                 aria-label="Mekan ara" />
        </label>
        <button className="icon-btn" aria-label="Filtreler" onClick={() => setFiltersOpen(true)} style={{ position: 'relative' }}>
          <SlidersHorizontal size={19} />
          {activeFilters > 0 && (
            <span className="badge badge-brand" style={{ position: 'absolute', top: -6, right: -6, padding: '1px 6px' }}>{activeFilters}</span>
          )}
        </button>
      </div>

      <div className="h-scroll" style={{ gap: 8 }}>
        <button className={`chip ${category === null ? 'active' : ''}`} onClick={() => setCategory(null)}>Tümü</button>
        {(Object.keys(CATEGORY_LABELS) as PlaceCategory[]).map(c => {
          const Icon = CATEGORY_ICON[c]
          return (
            <button key={c} className={`chip ${category === c ? 'active' : ''}`} aria-pressed={category === c}
                    onClick={() => setCategory(category === c ? null : c)}>
              <Icon size={15} /> {CATEGORY_LABELS[c]}
            </button>
          )
        })}
      </div>

      <Segmented value={mode} onChange={setMode}
                 options={[{ value: 'nearby', label: 'Yakınımda' }, { value: 'all', label: 'Tüm Kadıköy' }]} />

      {error && <ErrorState message={error} onRetry={() => void load(0)} />}

      {view === 'map' && places.length > 0 && (
        <RouteMap height={380} start={{ latitude: location.latitude, longitude: location.longitude }}
                  points={places.map((p, i) => ({ latitude: p.latitude, longitude: p.longitude, label: String(i + 1), title: p.name }))} />
      )}

      {!loading && places.length === 0 && !error && (
        <EmptyState icon={SearchX} title="Sonuç bulunamadı" text="Filtreleri değiştir ya da “Tüm Kadıköy” sekmesine bak." />
      )}

      <div className="stack">
        {places.map(p => <PlaceRow key={p.id} place={p} />)}
      </div>

      {loading && <ListSkeleton rows={places.length ? 1 : 4} />}
      {hasMore && !loading && (
        <button className="btn btn-secondary btn-block" onClick={() => void load(page + 1)}>Daha fazla göster</button>
      )}

      <Sheet open={filtersOpen} onClose={() => setFiltersOpen(false)} label="Filtreler">
        <div className="stack" style={{ gap: 20 }}>
          <div className="stack-sm">
            <span className="field-label">Kişi başı fiyat</span>
            <div className="chips">
              {PRICE_OPTIONS.map(o => (
                <button key={o.value} className={`chip ${maxCost === o.value ? 'active' : ''}`} onClick={() => setMaxCost(o.value)}>
                  {o.label}
                </button>
              ))}
            </div>
          </div>
          <button className={`list-item card ${indoorOnly ? '' : ''}`} style={{ borderRadius: 16 }} onClick={() => setIndoorOnly(v => !v)}
                  aria-pressed={indoorOnly}>
            <span className="list-item-icon"><Umbrella size={18} /></span>
            <span className="grow">
              <span style={{ display: 'block', fontWeight: 700 }}>Sadece kapalı alanlar</span>
              <span className="t-caption">Yağmurlu ve sıcak günler için</span>
            </span>
            <span className={`badge ${indoorOnly ? 'badge-success' : ''}`}>{indoorOnly ? 'Açık' : 'Kapalı'}</span>
          </button>
          <div className="row">
            <button className="btn btn-secondary grow" onClick={() => { setMaxCost(''); setIndoorOnly(false) }}>Temizle</button>
            <button className="btn btn-primary grow" onClick={() => setFiltersOpen(false)}>Uygula</button>
          </div>
        </div>
      </Sheet>
    </main>
  )
}
