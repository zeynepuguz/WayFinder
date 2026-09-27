import { List, Map as MapIcon, Search, SearchX, SlidersHorizontal, Umbrella } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { api } from '../api'
import type { Place, PlaceCategory } from '../api/types'
import { PlaceRow } from '../components/PlaceViews'
import { RouteMap } from '../components/RouteMap'
import { EmptyState, ErrorState, ListSkeleton, Segmented, Sheet } from '../components/ui'
import { CATEGORY_ICON } from '../components/visuals'
import { useUserLocation } from '../context/LocationContext'
import { CATEGORY_BY_SLUG, CATEGORY_LABELS } from '../lib/format'
import { useT } from '../lib/i18n'

type Mode = 'nearby' | 'all'
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

  const [mode, setMode] = useState<Mode>(slugCategory ? 'all' : 'nearby')
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

  const setCategory = (c: PlaceCategory | null) => slug
    ? navigate(c ? `/explore?category=${c}` : '/explore', { replace: true })
    : setParams(c ? { category: c } : {}, { replace: true })
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
        const q = fold(query)
        setPlaces(nearby.filter(p =>
          (!category || p.category === category)
          && (!indoorOnly || p.indoor)
          && (!maxCost || (p.estimatedCost ?? 0) <= Number(maxCost))
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

  return (
    <main className="screen">
      <div className="row-between">
        <h1 className="t-display">{t('Keşfet', 'Explore')}</h1>
        <button className="icon-btn" aria-label={view === 'list' ? t('Harita görünümü', 'Map view') : t('Liste görünümü', 'List view')}
                onClick={() => setView(v => (v === 'list' ? 'map' : 'list'))}>
          {view === 'list' ? <MapIcon size={20} /> : <List size={20} />}
        </button>
      </div>

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

      <Segmented value={mode} onChange={setMode}
                 options={[{ value: 'nearby', label: t('Yakınımda', 'Near me') }, { value: 'all', label: t('Tüm Kadıköy', 'All of Kadıköy') }]} />

      {error && <ErrorState message={error} onRetry={() => void load(0)} />}

      {view === 'map' && places.length > 0 && (
        <RouteMap height={380} start={{ latitude: location.latitude, longitude: location.longitude }}
                  points={places.map((p, i) => ({ latitude: p.latitude, longitude: p.longitude, label: String(i + 1), title: p.name }))} />
      )}

      {!loading && places.length === 0 && !error && (
        <EmptyState icon={SearchX} title={t('Sonuç bulunamadı', 'No results')} text={t('Filtreleri değiştir ya da “Tüm Kadıköy” sekmesine bak.', 'Change the filters or check the “All of Kadıköy” tab.')} />
      )}

      <div className="stack">
        {places.map(p => <PlaceRow key={p.id} place={p} />)}
      </div>

      {loading && <ListSkeleton rows={places.length ? 1 : 4} />}
      {hasMore && !loading && (
        <button className="btn btn-secondary btn-block" onClick={() => void load(page + 1)}>{t('Daha fazla göster', 'Show more')}</button>
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
          </div>
          <button className={`list-item card ${indoorOnly ? '' : ''}`} style={{ borderRadius: 16 }} onClick={() => setIndoorOnly(v => !v)}
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
