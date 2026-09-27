import { Search, SearchX, SlidersHorizontal, Umbrella } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { api } from '../api'
import type { Place, PlaceCategory } from '../api/types'
import { LiveMap } from '../components/LiveMap'
import { PlaceRow } from '../components/PlaceViews'
import { EmptyState, ErrorState, ListSkeleton, Segmented, Sheet } from '../components/ui'
import { CATEGORY_ICON } from '../components/visuals'
import { useUserLocation } from '../context/LocationContext'
import { CATEGORY_BY_SLUG, CATEGORY_LABELS, withinBudget } from '../lib/format'
import { useT } from '../lib/i18n'

type Mode = 'nearby' | 'all'
type View = 'list' | 'map'
const priceOptions = (t: (turkish: string, english: string) => string) => [
  { value: '', label: t('Hepsi', 'All') },
  { value: '0', label: t('Ücretsiz', 'Free') },
  { value: '200', label: '≤ 200 TL' },
  { value: '400', label: '≤ 400 TL' },
  { value: '700', label: '≤ 700 TL' },
]

export function ExplorePage() {
  const location = useUserLocation()
  const [params, setParams] = useSearchParams()
  // /kadikoy/kafe (crawlable category page) or /explore?category=CAFE
  const { slug } = useParams()
  const navigate = useNavigate()
  const t = useT()
  const slugCategory = slug ? CATEGORY_BY_SLUG[slug] ?? null : null
  const category = slugCategory ?? (params.get('category') as PlaceCategory | null) ?? null
  // Kept in the URL (?view=map) so the map is still there after opening a place and going back
  const view: View = params.get('view') === 'map' ? 'map' : 'list'

  const [mode, setMode] = useState<Mode>(slugCategory ? 'all' : 'nearby')
  const [query, setQuery] = useState('')
  const [indoorOnly, setIndoorOnly] = useState(false)
  const [maxCost, setMaxCost] = useState('')
  const [filtersOpen, setFiltersOpen] = useState(false)

  const [places, setPlaces] = useState<Place[]>([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Changes one search param and keeps the others (category + view)
  function updateParam(key: string, value: string | null) {
    if (slug) {
      // Leaving a category page: continue on /explore with the same state
      const next = new URLSearchParams()
      if (slugCategory && key !== 'category') next.set('category', slugCategory)
      if (view === 'map' && key !== 'view') next.set('view', 'map')
      if (value) next.set(key, value)
      const search = next.toString()
      navigate(search ? `/explore?${search}` : '/explore', { replace: true })
      return
    }
    setParams(current => {
      const next = new URLSearchParams(current)
      if (value) next.set(key, value)
      else next.delete(key)
      return next
    }, { replace: true })
  }

  const setCategory = (c: PlaceCategory | null) => updateParam('category', c)
  const setView = (v: View) => updateParam('view', v === 'map' ? 'map' : null)
  const activeFilters = (indoorOnly ? 1 : 0) + (maxCost ? 1 : 0)

  useEffect(() => {
    // The map loads its own places for the visible area
    if (view === 'map') return
    const timer = setTimeout(() => void load(0), query ? 300 : 0)
    return () => clearTimeout(timer)
    // Reload from the first page whenever a filter changes (search is debounced)
  }, [view, mode, category, query, indoorOnly, maxCost, location.latitude, location.longitude])

  async function load(nextPage: number) {
    setLoading(true)
    setError(null)
    try {
      if (mode === 'nearby') {
        // Nearby list is sorted by PostGIS distance; filters are applied on that small result
        const nearby = await api.nearbyPlaces(location.latitude, location.longitude, 2500, 50, category || undefined)
        const q = fold(query)
        setPlaces(nearby.filter(p =>
          (!category || p.category === category)
          && (!indoorOnly || p.indoor)
          && (!maxCost || withinBudget(p.estimatedCost, Number(maxCost)))
          && (!q || fold(p.name).includes(q))))
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
      setError(e instanceof Error ? e.message : t('Mekanlar yüklenemedi', 'Couldn’t load places'))
    } finally {
      setLoading(false)
    }
  }

  const categoryChips = (
    <div className="h-scroll" style={{ gap: 8 }}>
      <button className={`chip ${category === null ? 'active' : ''}`} onClick={() => setCategory(null)}>{t('Tümü', 'All')}</button>
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
  )

  return (
    <main className={`screen ${view === 'map' ? 'screen-map' : ''}`}>
      <div className="row-between">
        <h1 className="t-display">{t('Keşfet', 'Explore')}</h1>
        <div className="view-toggle">
          <Segmented value={view} onChange={setView}
                     options={[{ value: 'list', label: t('Liste', 'List') }, { value: 'map', label: t('Harita', 'Map') }]} />
        </div>
      </div>

      {view === 'map' ? (
        <>
          {categoryChips}
          <LiveMap category={category} />
        </>
      ) : (
        <>
          <div className="row">
            <label className="search grow">
              <Search size={18} />
              <input type="search" placeholder={t('Mekan, kafe, müze ara', 'Search places, cafés, museums')} value={query} onChange={e => setQuery(e.target.value)}
                     aria-label={t('Mekan ara', 'Search places')} />
            </label>
            <button className="icon-btn" aria-label={t('Filtreler', 'Filters')} onClick={() => setFiltersOpen(true)} style={{ position: 'relative' }}>
              <SlidersHorizontal size={19} />
              {activeFilters > 0 && (
                <span className="badge badge-brand" style={{ position: 'absolute', top: -6, right: -6, padding: '1px 6px' }}>{activeFilters}</span>
              )}
            </button>
          </div>

          {categoryChips}

          <Segmented value={mode} onChange={setMode}
                     options={[{ value: 'nearby', label: t('Yakınımda', 'Near me') }, { value: 'all', label: t('Tüm İstanbul', 'All of Istanbul') }]} />

          {error && <ErrorState message={error} onRetry={() => void load(0)} />}

          {!loading && places.length === 0 && !error && (
            <EmptyState icon={SearchX} title={t('Sonuç bulunamadı', 'No results')}
                        text={t('Filtreleri değiştir ya da “Tüm İstanbul” sekmesine bak.', 'Change the filters or check the “All of Istanbul” tab.')} />
          )}

          <div className="stack">
            {places.map(p => <PlaceRow key={p.id} place={p} />)}
          </div>

          {loading && <ListSkeleton rows={places.length ? 1 : 4} />}
          {hasMore && !loading && (
            <button className="btn btn-secondary btn-block" onClick={() => void load(page + 1)}>{t('Daha fazla göster', 'Show more')}</button>
          )}
        </>
      )}

      <Sheet open={filtersOpen} onClose={() => setFiltersOpen(false)} label={t('Filtreler', 'Filters')}>
        <div className="stack" style={{ gap: 20 }}>
          <div className="stack-sm">
            <span className="field-label">{t('Kişi başı fiyat', 'Price per person')}</span>
            <div className="chips">
              {priceOptions(t).map(o => (
                <button key={o.value} className={`chip ${maxCost === o.value ? 'active' : ''}`} onClick={() => setMaxCost(o.value)}>
                  {o.label}
                </button>
              ))}
            </div>
            {maxCost && (
              <span className="t-caption">{t('Fiyat bilgisi olmayan mekanlar bu filtrede gösterilmez.', 'Places without price info are hidden by this filter.')}</span>
            )}
          </div>
          <button className="list-item card" style={{ borderRadius: 16 }} onClick={() => setIndoorOnly(v => !v)}
                  aria-pressed={indoorOnly}>
            <span className="list-item-icon"><Umbrella size={18} /></span>
            <span className="grow">
              <span style={{ display: 'block', fontWeight: 700 }}>{t('Sadece kapalı alanlar', 'Indoor places only')}</span>
              <span className="t-caption">{t('Yağmurlu ve sıcak günler için', 'For rainy and hot days')}</span>
            </span>
            <span className={`badge ${indoorOnly ? 'badge-success' : ''}`}>{indoorOnly ? t('Açık', 'On') : t('Kapalı', 'Off')}</span>
          </button>
          <div className="row">
            <button className="btn btn-secondary grow" onClick={() => { setMaxCost(''); setIndoorOnly(false) }}>{t('Temizle', 'Clear')}</button>
            <button className="btn btn-primary grow" onClick={() => setFiltersOpen(false)}>{t('Uygula', 'Apply')}</button>
          </div>
        </div>
      </Sheet>
    </main>
  )
}

// "kadikoy" matches "Kadıköy": case- and diacritic-insensitive, so English keyboards work too
function fold(text: string): string {
  return text.toLocaleLowerCase('tr').replace(/ı/g, 'i').normalize('NFD').replace(/[̀-ͯ]/g, '')
}
