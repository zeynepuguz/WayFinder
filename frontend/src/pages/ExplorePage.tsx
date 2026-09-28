import { Check, ChevronDown, Map as MapIcon, MapPin, Search, SearchX, SlidersHorizontal, Square, SquareCheck, Umbrella } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { api } from '../api'
import type { City, District, Place, PlaceCategory } from '../api/types'
import { HScroll } from '../components/HScroll'
import { LiveMap } from '../components/LiveMap'
import { PlaceRow } from '../components/PlaceViews'
import { PopularRoutes } from '../components/PopularRoutes'
import { EmptyState, ErrorState, ListSkeleton, Segmented, Sheet, Skeleton } from '../components/ui'
import { CATEGORY_ICON } from '../components/visuals'
import { DEFAULT_CITY, useCity } from '../context/CityContext'
import { useUserLocation } from '../context/LocationContext'
import { filterByName, filterDistricts, useDistricts } from '../lib/districts'
import { appendUnique, CATEGORY_BY_SLUG, CATEGORY_LABELS, fold, withinBudget } from '../lib/format'
import { locale, useT } from '../lib/i18n'

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

  // City (?sehir=ankara) in the URL when chosen here; otherwise the app-wide city (saved / detected).
  // The crawlable /kadikoy/... pages are about Istanbul.
  const cityState = useCity()
  const urlCity = params.get('sehir') ?? (slug ? DEFAULT_CITY : null)
  const { cities } = cityState
  // Unknown slugs in the URL are ignored once the list is known
  const citySlug = urlCity && (!cities.length || cities.some(c => c.slug === urlCity)) ? urlCity : cityState.citySlug
  const city: City | null = cities.find(c => c.slug === citySlug) ?? null
  const cityName = city?.name ?? null
  const [cityOpen, setCityOpen] = useState(false)
  // "Popular routes" ticked (?rotalar=1): ready-made routes instead of the place list (list view only)
  const popular = view === 'list' && params.get('rotalar') === '1'

  // District (ilçe) in the URL (?ilce=kadikoy): shareable and kept on back navigation
  const districts = useDistricts(citySlug)
  const districtParam = params.get('ilce')
  const district: District | null = districts.data?.find(d => d.slug === districtParam) ?? null
  // Until the list arrives the slug is used as is; afterwards unknown slugs are ignored
  const districtSlug = districtParam && (!districts.data || district) ? districtParam : null
  const [districtOpen, setDistrictOpen] = useState(false)

  const [mode, setMode] = useState<Mode>(slugCategory || districtParam || params.get('sehir') ? 'all' : 'nearby')
  const [query, setQuery] = useState('')
  const [indoorOnly, setIndoorOnly] = useState(false)
  const [maxCost, setMaxCost] = useState('')
  const [filtersOpen, setFiltersOpen] = useState(false)

  const [places, setPlaces] = useState<Place[]>([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // A district chosen (or restored from the URL) shows that district's list
  useEffect(() => {
    if (districtSlug) setMode('all')
  }, [districtSlug])

  // Changes some search params (null removes one) and keeps the others (category, view, city, district)
  function updateParams(changes: Record<string, string | null>) {
    const apply = (next: URLSearchParams) => {
      Object.entries(changes).forEach(([key, value]) => {
        if (value) next.set(key, value)
        else next.delete(key)
      })
      return next
    }
    if (slug) {
      // Leaving a category page: continue on /explore with the same state (Istanbul, the category)
      const next = new URLSearchParams(params)
      if (slugCategory) next.set('category', slugCategory)
      next.set('sehir', DEFAULT_CITY)
      const search = apply(next).toString()
      navigate(search ? `/explore?${search}` : '/explore', { replace: true })
      return
    }
    setParams(current => apply(new URLSearchParams(current)), { replace: true })
  }

  const setCategory = (c: PlaceCategory | null) => updateParams({ category: c })
  // The map and the popular routes exclude each other: each route card has its own map
  const setView = (v: View) => updateParams(v === 'map' ? { view: 'map', rotalar: null } : { view: null })
  const togglePopular = () => updateParams({ rotalar: popular ? null : '1', view: null })
  const chooseCity = (c: City) => {
    setCityOpen(false)
    cityState.setCity(c.slug)
    // A new city starts without a district
    updateParams({ sehir: c.slug, ilce: null })
    setMode('all')
  }
  const chooseDistrict = (d: District | null) => {
    setDistrictOpen(false)
    updateParams({ ilce: d?.slug ?? null })
    if (d) setMode('all')
  }
  const activeFilters = (indoorOnly ? 1 : 0) + (maxCost ? 1 : 0)

  useEffect(() => {
    // The map loads its own places for the visible area; popular routes load their own data
    if (view === 'map' || popular) return
    // The city list waits until the city is known (saved choice or detection)
    if (mode === 'all' && !citySlug) return
    const timer = setTimeout(() => void load(0), query ? 300 : 0)
    return () => clearTimeout(timer)
    // Reload from the first page whenever a filter changes (search is debounced)
  }, [view, popular, mode, category, citySlug, districtSlug, query, indoorOnly, maxCost, location.latitude, location.longitude])

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
          city: citySlug ?? undefined,
          district: districtSlug ?? undefined,
          q: query || undefined,
          indoor: indoorOnly || undefined,
          maxCost: maxCost ? Number(maxCost) : undefined,
          page: nextPage,
          size: 20,
        })
        // A place can shift between pages while data changes: never list it twice
        setPlaces(current => (nextPage === 0 ? result.content : appendUnique(current, result.content)))
        setHasMore(result.page + 1 < result.totalPages)
      }
      setPage(nextPage)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Mekanlar yüklenemedi', 'Couldn’t load places'))
    } finally {
      setLoading(false)
    }
  }

  // Name once the list is loaded; a neutral word meanwhile (never the raw slug)
  const districtName = district?.name ?? (districtSlug ? t('İlçe', 'District') : null)
  const pillLabel = districtName ?? t('Tüm ilçeler', 'All districts')
  const cityLabel = cityName ?? t('Şehir', 'City')
  // Second tab: the chosen district, else the whole city
  const areaLabel = districtName ?? cityLabel

  // [City ▾] [District ▾] [☐ Popular routes]
  const controls = (
    <div className="explore-controls">
      <button className="chip city-pill" onClick={() => setCityOpen(true)}
              aria-haspopup="dialog" aria-label={`${t('Şehir seç', 'Choose city')}: ${cityLabel}`}>
        <MapPin size={15} /> <span>{cityLabel}</span> <ChevronDown size={15} />
      </button>
      <button className={`chip district-pill ${districtSlug ? 'active' : ''}`} onClick={() => setDistrictOpen(true)}
              disabled={!citySlug}
              aria-haspopup="dialog" aria-label={`${t('İlçe seç', 'Choose district')}: ${pillLabel}`}>
        <MapIcon size={15} /> <span>{pillLabel}</span> <ChevronDown size={15} />
      </button>
      <button className={`chip ${popular ? 'active' : ''}`} onClick={togglePopular} aria-pressed={popular}>
        {popular ? <SquareCheck size={15} /> : <Square size={15} />} {t('Popüler rotalar', 'Popular routes')}
      </button>
    </div>
  )

  const categoryChips = (
    <HScroll className="h-scroll chip-scroll" style={{ gap: 8 }} label={t('Kategoriler', 'Categories')}>
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
    </HScroll>
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

      {controls}

      {view === 'map' ? (
        <>
          {categoryChips}
          <LiveMap category={category} focusArea={district} focusCity={city} />
        </>
      ) : popular ? (
        // Category chips, search and filters are about places: not shown for the routes
        citySlug ? <PopularRoutes city={citySlug} district={districtSlug} /> : <ListSkeleton rows={3} height={180} />
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
                     options={[
                       { value: 'nearby', label: t('Yakınımda', 'Near me') },
                       { value: 'all', label: areaLabel },
                     ]} />

          {error && <ErrorState message={error} onRetry={() => void load(0)} />}

          {!loading && places.length === 0 && !error && (
            <EmptyState icon={SearchX} title={t('Sonuç bulunamadı', 'No results')}
                        text={mode === 'nearby'
                          ? t(`Filtreleri değiştir ya da “${areaLabel}” sekmesine bak.`, `Change the filters or check the “${areaLabel}” tab.`)
                          : districtSlug
                            ? t('Filtreleri değiştir ya da başka bir ilçe seç.', 'Change the filters or choose another district.')
                            : t('Filtreleri değiştir ya da başka bir şehir seç.', 'Change the filters or choose another city.')} />
          )}

          <div className="stack">
            {/* City-wide list: the district tells same-named places apart (redundant once a district is chosen) */}
            {places.map(p => <PlaceRow key={p.id} place={p} showDistrict={mode === 'all' && !districtSlug} />)}
          </div>

          {loading && <ListSkeleton rows={places.length ? 1 : 4} />}
          {hasMore && !loading && (
            <button className="btn btn-secondary btn-block" onClick={() => void load(page + 1)}>{t('Daha fazla göster', 'Show more')}</button>
          )}
        </>
      )}

      <CitySheet open={cityOpen} onClose={() => setCityOpen(false)} selected={citySlug} state={cityState} onChoose={chooseCity} />

      <DistrictSheet open={districtOpen} onClose={() => setDistrictOpen(false)} selected={districtSlug}
                     districts={districts} onChoose={chooseDistrict} />

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

// Bottom sheet: search field, "All districts" and every district with its number of places
function DistrictSheet({ open, onClose, selected, districts, onChoose }: {
  open: boolean
  onClose: () => void
  selected: string | null
  districts: ReturnType<typeof useDistricts>
  onChoose: (district: District | null) => void
}) {
  const t = useT()
  const [search, setSearch] = useState('')
  useEffect(() => {
    if (!open) setSearch('')
  }, [open])

  const list = districts.data ? filterDistricts(districts.data, search) : []
  const count = (n: number) => `${n.toLocaleString(locale())} ${t('mekan', n === 1 ? 'place' : 'places')}`

  return (
    <Sheet open={open} onClose={onClose} label={t('İlçe seç', 'Choose district')}>
      <div className="stack">
        <label className="search">
          <Search size={18} />
          <input type="search" placeholder={t('İlçe ara', 'Search districts')} value={search} onChange={e => setSearch(e.target.value)}
                 aria-label={t('İlçe ara', 'Search districts')} />
        </label>

        {districts.error && <ErrorState message={districts.error} onRetry={() => void districts.reload()} />}

        <div className="list-group district-list">
          {!search.trim() && (
            <button className="list-item" aria-pressed={selected == null} onClick={() => onChoose(null)}>
              <span className="list-item-icon"><MapPin size={18} /></span>
              <span className="grow" style={{ fontWeight: 700 }}>{t('Tüm ilçeler', 'All districts')}</span>
              {selected == null && <Check size={18} color="var(--brand)" />}
            </button>
          )}
          {districts.loading && !districts.data && (
            <div className="stack" style={{ padding: 12 }}>
              {[0, 1, 2, 3].map(i => <Skeleton key={i} height={40} radius={12} />)}
            </div>
          )}
          {list.map(d => (
            <button key={d.slug} className="list-item" aria-pressed={selected === d.slug} onClick={() => onChoose(d)}>
              <span className="grow" style={{ fontWeight: 650 }}>{d.name}</span>
              <span className="t-caption">{count(d.placeCount)}</span>
              {selected === d.slug && <Check size={18} color="var(--brand)" />}
            </button>
          ))}
          {districts.data && search.trim() && list.length === 0 && (
            <p className="t-caption" style={{ padding: 16 }}>{t('Bu isimde bir ilçe yok.', 'No district with that name.')}</p>
          )}
        </div>
      </div>
    </Sheet>
  )
}

// Bottom sheet: search field and all 81 cities; cities without places yet are shown as "coming soon"
function CitySheet({ open, onClose, selected, state, onChoose }: {
  open: boolean
  onClose: () => void
  selected: string | null
  state: ReturnType<typeof useCity>
  onChoose: (city: City) => void
}) {
  const t = useT()
  const [search, setSearch] = useState('')
  useEffect(() => {
    if (!open) setSearch('')
  }, [open])

  const list = filterByName(state.cities, search)
  const count = (n: number) => `${n.toLocaleString(locale())} ${t('mekan', n === 1 ? 'place' : 'places')}`

  return (
    <Sheet open={open} onClose={onClose} label={t('Şehir seç', 'Choose city')}>
      <div className="stack">
        <label className="search">
          <Search size={18} />
          <input type="search" placeholder={t('Şehir ara', 'Search cities')} value={search} onChange={e => setSearch(e.target.value)}
                 aria-label={t('Şehir ara', 'Search cities')} />
        </label>

        {state.error && <ErrorState message={state.error} onRetry={state.reload} />}

        <div className="list-group district-list city-list">
          {state.loading && !state.cities.length && (
            <div className="stack" style={{ padding: 12 }}>
              {[0, 1, 2, 3].map(i => <Skeleton key={i} height={40} radius={12} />)}
            </div>
          )}
          {list.map(c => {
            const soon = c.placeCount === 0
            return (
              <button key={c.slug} className="list-item" aria-pressed={selected === c.slug} disabled={soon}
                      onClick={() => onChoose(c)}>
                <span className="grow" style={{ fontWeight: 650 }}>{c.name}</span>
                {soon
                  ? <span className="badge">{t('yakında', 'coming soon')}</span>
                  : <span className="t-caption">{count(c.placeCount)}</span>}
                {selected === c.slug && <Check size={18} color="var(--brand)" />}
              </button>
            )
          })}
          {state.cities.length > 0 && search.trim() && list.length === 0 && (
            <p className="t-caption" style={{ padding: 16 }}>{t('Bu isimde bir şehir yok.', 'No city with that name.')}</p>
          )}
        </div>
      </div>
    </Sheet>
  )
}
