import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { City, District, Page, Place, PopularRoute } from '../api/types'
import { resetDistrictCache } from '../lib/districts'
import { place } from '../test/fixtures'
import { ExplorePage, resetExploreListCache } from './ExplorePage'

const district = (slug: string, name: string, placeCount: number): District => ({
  slug, name, placeCount, latitude: 41, longitude: 29, south: 40.9, west: 28.9, north: 41.1, east: 29.1,
})
const DISTRICTS = [district('besiktas', 'Beşiktaş', 812), district('kadikoy', 'Kadıköy', 1234), district('uskudar', 'Üsküdar', 1)]
const city = (slug: string, name: string, placeCount: number): City => ({
  slug, name, placeCount, districtCount: 10, latitude: 39.92, longitude: 32.85, south: 39.5, west: 32.3, north: 40.3, east: 33.3,
})
const CITIES = [city('ankara', 'Ankara', 5400), city('istanbul', 'İstanbul', 61000), city('izmir', 'İzmir', 8000), city('van', 'Van', 0)]
const ANKARA_DISTRICTS = [district('cankaya', 'Çankaya', 900)]
const page = (content: Place[], pageNo = 0, totalPages = 1): Page<Place> =>
  ({ content, page: pageNo, size: 20, totalElements: content.length, totalPages })

const districts = vi.fn()
const searchPlaces = vi.fn()
const nearbyPlaces = vi.fn()
const popularRoutes = vi.fn()
const startPopularRoute = vi.fn()
const communityRoutes = vi.fn()
const startCommunityRoute = vi.fn()
const favoritePlaces = vi.fn()
const photos = vi.fn()
vi.mock('../api', () => ({
  api: {
    districts: (...args: unknown[]) => districts(...args),
    searchPlaces: (...args: unknown[]) => searchPlaces(...args),
    nearbyPlaces: (...args: unknown[]) => nearbyPlaces(...args),
    popularRoutes: (...args: unknown[]) => popularRoutes(...args),
    startPopularRoute: (...args: unknown[]) => startPopularRoute(...args),
    communityRoutes: (...args: unknown[]) => communityRoutes(...args),
    startCommunityRoute: (...args: unknown[]) => startCommunityRoute(...args),
    favoritePlaces: (...args: unknown[]) => favoritePlaces(...args),
    photos: (...args: unknown[]) => photos(...args),
  },
}))
const liveMapProps = vi.fn()
vi.mock('../components/LiveMap', () => ({ LiveMap: (props: unknown) => { liveMapProps(props); return null } }))
vi.mock('../components/RouteMap', () => ({ RouteMap: () => <div data-testid="route-map" /> }))
vi.mock('../context/LocationContext', () => ({ useUserLocation: () => ({ latitude: 40.99, longitude: 29.02 }) }))
let auth = { user: null as null | { id: number }, hasAccess: false }
vi.mock('../context/AuthContext', () => ({ useAuth: () => auth }))
const setCity = vi.fn()
vi.mock('../context/CityContext', () => ({
  DEFAULT_CITY: 'istanbul',
  useCity: () => ({
    city: CITIES[1], citySlug: 'istanbul', cities: CITIES, loading: false, error: null, reload: () => {}, setCity,
  }),
}))
vi.mock('../context/SavedPlacesContext', () => ({ useSavedPlaces: () => ({ isSaved: () => false, toggle: vi.fn() }) }))

let search = ''
function Probe() {
  search = useLocation().search
  return null
}

function renderPage(url = '/explore') {
  render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/explore" element={<><ExplorePage /><Probe /></>} />
        <Route path="/routes/:id" element={<p>Rota sayfası</p>} />
        <Route path="/login" element={<p>Giriş sayfası</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  resetExploreListCache()
  resetDistrictCache()
  districts.mockReset().mockImplementation((slug: string) => Promise.resolve(slug === 'ankara' ? ANKARA_DISTRICTS : DISTRICTS))
  popularRoutes.mockReset().mockResolvedValue([])
  startPopularRoute.mockReset()
  communityRoutes.mockReset().mockResolvedValue([])
  startCommunityRoute.mockReset()
  favoritePlaces.mockReset().mockResolvedValue([])
  photos.mockReset().mockResolvedValue([])
  liveMapProps.mockReset()
  setCity.mockReset()
  auth = { user: null, hasAccess: false }
  searchPlaces.mockReset().mockResolvedValue(page([place(1, 'Moda Kahve', { district: 'Kadıköy' })]))
  nearbyPlaces.mockReset().mockResolvedValue([])
})

describe('ExplorePage districts', () => {
  it('picks a district from the searchable sheet and lists only its places', async () => {
    renderPage('/explore?category=CAFE')
    await userEvent.click(screen.getByRole('button', { name: /İlçe seç: Tüm ilçeler/ }))
    const sheet = await screen.findByRole('dialog', { name: 'İlçe seç' })
    expect(within(sheet).getByRole('button', { name: /Tüm ilçeler/ })).toHaveAttribute('aria-pressed', 'true')
    expect(within(sheet).getByText('1.234 mekan')).toBeInTheDocument()

    // case- and diacritic-insensitive search
    await userEvent.type(within(sheet).getByRole('searchbox', { name: 'İlçe ara' }), 'KADIKOY')
    expect(within(sheet).queryByText('Beşiktaş')).not.toBeInTheDocument()
    await userEvent.click(within(sheet).getByRole('button', { name: /Kadıköy/ }))

    await waitFor(() => expect(searchPlaces).toHaveBeenLastCalledWith(
      expect.objectContaining({ city: 'istanbul', district: 'kadikoy', category: 'CAFE', page: 0 })))
    expect(districts).toHaveBeenCalledWith('istanbul')
    expect(search).toContain('ilce=kadikoy')
    expect(search).toContain('category=CAFE')
    expect(screen.getByRole('tab', { name: 'Kadıköy' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Yakınımda' })).toBeInTheDocument()
    expect(await screen.findByText('Moda Kahve')).toBeInTheDocument()
    // district already in the tab: not repeated on every row
    expect(screen.queryByText('Kafe · Kadıköy')).not.toBeInTheDocument()
  })

  it('restores the district from the URL and clears it with "All districts"', async () => {
    renderPage('/explore?ilce=uskudar')
    expect(await screen.findByRole('tab', { name: 'Üsküdar' })).toHaveAttribute('aria-selected', 'true')
    expect(searchPlaces).toHaveBeenCalledWith(expect.objectContaining({ district: 'uskudar' }))

    await userEvent.click(screen.getByRole('button', { name: /İlçe seç: Üsküdar/ }))
    const sheet = await screen.findByRole('dialog', { name: 'İlçe seç' })
    expect(within(sheet).getByText('1 mekan')).toBeInTheDocument()
    await userEvent.click(within(sheet).getByRole('button', { name: /Tüm ilçeler/ }))

    expect(await screen.findByRole('tab', { name: 'İstanbul' })).toHaveAttribute('aria-selected', 'true')
    expect(search).not.toContain('ilce')
    await waitFor(() => expect(searchPlaces).toHaveBeenLastCalledWith(expect.objectContaining({ district: undefined })))
    // city-wide rows name the district
    expect(await screen.findByText('Kafe · Kadıköy')).toBeInTheDocument()
  })

  it('shows the district’s user photos strip with a Google Maps link', async () => {
    photos.mockResolvedValue([{ id: 1, url: '/media/photos/1.jpg', thumbUrl: '/media/photos/1_t.jpg', width: 800, height: 600, createdAt: '2026-09-01T10:00:00Z', uploader: 'Ayşe Y.' }])
    renderPage('/explore?ilce=kadikoy')
    const strip = await screen.findByRole('region', { name: 'Kadıköy’den kareler' })
    await waitFor(() => expect(photos).toHaveBeenCalledWith({ type: 'DISTRICT', city: 'istanbul', district: 'kadikoy' }))
    expect(await within(strip).findByRole('img', { name: 'Kadıköy — kullanıcı fotoğrafı' })).toHaveAttribute('src', '/media/photos/1_t.jpg')
    const link = within(strip).getByRole('link', { name: /Google Haritalar/ })
    expect(new URL(link.getAttribute('href')!).searchParams.get('query')).toBe('Kadıköy, İstanbul')

    // guests go to login first
    await userEvent.click(within(strip).getByRole('button', { name: /Fotoğraf ekle/ }))
    expect(await screen.findByText('Giriş sayfası')).toBeInTheDocument()
  })

  it('does not list a place twice when it shows up again on the next page', async () => {
    searchPlaces
      .mockResolvedValueOnce(page([place(1, 'Moda Kahve'), place(2, 'Yeldeğirmeni Fırın')], 0, 2))
      .mockResolvedValueOnce(page([place(2, 'Yeldeğirmeni Fırın'), place(3, 'Kuzguncuk Çay')], 1, 2))
    renderPage('/explore?ilce=kadikoy')
    await userEvent.click(await screen.findByRole('button', { name: 'Daha fazla göster' }))
    expect(await screen.findByText('Kuzguncuk Çay')).toBeInTheDocument()
    expect(screen.getAllByText('Yeldeğirmeni Fırın')).toHaveLength(1)
  })
})

const route = (key: string, title: string): PopularRoute => ({
  key, title, description: 'Bu bölgenin en çok ilgi gören yerleri, yürüme sırasıyla.',
  popularityNote: 'Wikipedia’da en çok okunan yerler', startLabel: 'Ankara Kalesi', startLatitude: 39.94, startLongitude: 32.86,
  date: '2026-09-28', totalWalkingMinutes: 42, estimatedCostPerPerson: 150, unknownPriceStops: 1,
  stops: [
    { time: '09:30', durationMinutes: 45, type: 'SIGHTSEEING', typeLabel: 'Gezi', walkingMinutes: 0, distanceMeters: 0,
      place: { id: 11, name: 'Ankara Kalesi', category: 'ATTRACTION', latitude: 39.94, longitude: 32.86, image: null,
        estimatedCost: 0, verified: false, district: 'Altındağ', popularity: 14.2 } },
    { time: '12:00', durationMinutes: 60, type: 'LUNCH', typeLabel: 'Öğle yemeği', walkingMinutes: 8, distanceMeters: 600,
      place: { id: 12, name: 'Kale Lokantası', category: 'RESTAURANT', latitude: 39.93, longitude: 32.85, image: null,
        estimatedCost: null, verified: false, district: 'Altındağ', popularity: null } },
  ],
})

describe('ExplorePage cities', () => {
  it('replaces "All of Istanbul" with the chosen city and switches city from the sheet', async () => {
    renderPage('/explore?ilce=kadikoy&category=CAFE&view=map')
    expect(await screen.findByRole('button', { name: 'Şehir seç: İstanbul' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Şehir seç: İstanbul' }))
    const sheet = await screen.findByRole('dialog', { name: 'Şehir seç' })
    // cities without places are listed but cannot be chosen
    expect(within(sheet).getByRole('button', { name: /Van/ })).toBeDisabled()
    expect(within(sheet).getByText('yakında')).toBeInTheDocument()
    expect(within(sheet).getByText('5.400 mekan')).toBeInTheDocument()

    await userEvent.type(within(sheet).getByRole('searchbox', { name: 'Şehir ara' }), 'IZMIR')
    expect(within(sheet).queryByText('Ankara')).not.toBeInTheDocument()
    await userEvent.clear(within(sheet).getByRole('searchbox', { name: 'Şehir ara' }))
    await userEvent.click(within(sheet).getByRole('button', { name: /Ankara/ }))

    expect(setCity).toHaveBeenCalledWith('ankara')
    // the district is reset, the rest of the state is kept
    expect(search).toContain('sehir=ankara')
    expect(search).not.toContain('ilce')
    expect(search).toContain('view=map')
    expect(search).toContain('category=CAFE')
    await waitFor(() => expect(liveMapProps).toHaveBeenLastCalledWith(expect.objectContaining({
      focusCity: expect.objectContaining({ slug: 'ankara' }), focusArea: null,
    })))
    expect(screen.getByRole('button', { name: 'Şehir seç: Ankara' })).toBeInTheDocument()
  })

  it('lists the city and its districts from the URL', async () => {
    renderPage('/explore?sehir=ankara')
    expect(await screen.findByRole('tab', { name: 'Ankara' })).toHaveAttribute('aria-selected', 'true')
    await waitFor(() => expect(searchPlaces).toHaveBeenLastCalledWith(expect.objectContaining({ city: 'ankara', district: undefined })))

    await userEvent.click(screen.getByRole('button', { name: /İlçe seç: Tüm ilçeler/ }))
    const sheet = await screen.findByRole('dialog', { name: 'İlçe seç' })
    expect(await within(sheet).findByText('Çankaya')).toBeInTheDocument()
    expect(within(sheet).queryByText('Kadıköy')).not.toBeInTheDocument()
    expect(districts).toHaveBeenCalledWith('ankara')
  })
})

describe('ExplorePage popular routes and favourite places', () => {
  it('shows the top two of both boxes side by side above the places; a box title opens it', async () => {
    communityRoutes.mockResolvedValue([route('c-1', 'Kocatepe turu'), route('c-2', 'Ulus turu'), route('c-3', 'Üçüncü tur')])
    favoritePlaces.mockResolvedValue([place(5, 'Kızılay Kahve', { userRating: 4.6, userRatingCount: 12 })])
    renderPage('/explore?sehir=ankara')

    expect(await screen.findByText('Kocatepe turu')).toBeInTheDocument()
    expect(screen.getByText('Ulus turu')).toBeInTheDocument()
    expect(screen.queryByText('Üçüncü tur')).not.toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /Kızılay Kahve/ })).toHaveAttribute('href', '/places/5')
    expect(screen.getByText(/12 oy/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Favori mekanlar' }))
    expect(search).toContain('favoriler=1')
    // back to the place list
    await userEvent.click(screen.getByRole('button', { name: /Mekanlar/ }))
    expect(search).not.toContain('favoriler=1')
  })

  it('“Popüler rotalar” shows the city’s favourite routes instead of the place list', async () => {
    communityRoutes.mockResolvedValue([route('c-1a-2b', 'Kocatepe ve 2 durak daha')])
    renderPage('/explore?sehir=ankara&category=CAFE')
    await userEvent.click(await screen.findByRole('button', { name: 'Popüler rotalar' }))

    expect(search).toContain('rotalar=1')
    expect(screen.getByRole('button', { name: 'Popüler rotalar' })).toHaveAttribute('aria-pressed', 'true')
    expect(await screen.findByText('Kocatepe ve 2 durak daha')).toBeInTheDocument()
    expect(communityRoutes).toHaveBeenCalledWith('ankara', undefined)
    // Places are the other box (not shown); the famous-sights routes are not the favourites
    expect(screen.queryByText('Ankara: favori mekanlar')).not.toBeInTheDocument()
    expect(popularRoutes).not.toHaveBeenCalled()
    // categories and the place tabs are about places
    expect(screen.queryByRole('button', { name: 'Tümü' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Yakınımda' })).not.toBeInTheDocument()
  })

  it('“Favori mekanlar” is its own box, and only one box is open at a time', async () => {
    favoritePlaces.mockResolvedValue([place(5, 'Kızılay Kahve', { district: 'Çankaya', userRating: 4.6, userRatingCount: 12 })])
    renderPage('/explore?sehir=ankara&rotalar=1')
    await userEvent.click(await screen.findByRole('button', { name: 'Favori mekanlar' }))

    expect(search).toContain('favoriler=1')
    expect(search).not.toContain('rotalar=1')
    expect(screen.getByRole('button', { name: 'Favori mekanlar' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Popüler rotalar' })).toHaveAttribute('aria-pressed', 'false')
    expect(await screen.findByText('Kızılay Kahve')).toBeInTheDocument()
    expect(screen.getByText('Ankara: favori mekanlar')).toBeInTheDocument()
    // Ranked by the users' stars, which the row shows with the vote count
    expect(screen.getByText('4.6')).toBeInTheDocument()
    expect(screen.getByText(/· 12 oy/)).toBeInTheDocument()
    expect(favoritePlaces).toHaveBeenCalledWith('ankara', undefined)
  })

  it('only the district’s favourites when a district is chosen', async () => {
    renderPage('/explore?sehir=istanbul&ilce=kadikoy&rotalar=1')
    await waitFor(() => expect(communityRoutes).toHaveBeenCalledWith('istanbul', 'kadikoy'))
    renderPage('/explore?sehir=istanbul&ilce=kadikoy&favoriler=1')
    await waitFor(() => expect(favoritePlaces).toHaveBeenCalledWith('istanbul', 'kadikoy'))
  })

  it('all Türkiye from the city list, without a district, in both boxes', async () => {
    renderPage('/explore?sehir=istanbul&ilce=kadikoy&rotalar=1')
    await userEvent.click(await screen.findByRole('button', { name: /Şehir seç/ }))
    await userEvent.click(within(await screen.findByRole('dialog', { name: 'Şehir seç' })).getByRole('button', { name: 'Tüm Türkiye' }))

    expect(search).toContain('sehir=turkiye')
    expect(search).not.toContain('ilce=')
    await waitFor(() => expect(communityRoutes).toHaveBeenLastCalledWith(undefined, undefined))
    expect(screen.getByRole('button', { name: /İlçe seç/ })).toBeDisabled()
    expect(screen.getByText('Türkiye: popüler rotalar')).toBeInTheDocument()

    // The other box keeps all Türkiye
    await userEvent.click(screen.getByRole('button', { name: 'Favori mekanlar' }))
    await waitFor(() => expect(favoritePlaces).toHaveBeenLastCalledWith(undefined, undefined))
    expect(screen.getByText('Türkiye: favori mekanlar')).toBeInTheDocument()
  })

  it('offers the famous-sights routes while the area has no favourite routes', async () => {
    popularRoutes.mockResolvedValue([route('a1-3f', 'Ulus ve çevresi')])
    renderPage('/explore?sehir=ankara&rotalar=1')
    expect(await screen.findByText('Henüz favori rota yok')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Ünlü yerler rotalarına bak' }))

    expect(await screen.findByText('Ulus ve çevresi')).toBeInTheDocument()
    expect(popularRoutes).toHaveBeenCalledWith('ankara', undefined)
    expect(screen.getByText('2 durak')).toBeInTheDocument()
    expect(screen.getByText(/42 dk yürüyüş/)).toBeInTheDocument()
    expect(screen.getByText(/Kişi başı ~150 TL · 1 durağın fiyatı bilinmiyor/)).toBeInTheDocument()
    expect(screen.getByText('12:00 · Öğle yemeği')).toBeInTheDocument()
    expect(screen.getByText('Wikipedia’da en çok okunan yerler')).toBeInTheDocument()
    expect(screen.getByText('09:30 · Gezi')).toBeInTheDocument()
    expect(document.querySelector('[data-stop-type="LUNCH"]')).not.toBeNull()
    expect(screen.getByRole('link', { name: 'Ankara Kalesi' })).toHaveAttribute('href', '/places/11')

    // the map and the start button appear once the card is opened
    expect(screen.queryByTestId('route-map')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Ulus ve çevresi/ }))
    expect(screen.getByTestId('route-map')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Ulus ve çevresi/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('sends guests to login before starting a favourite route', async () => {
    communityRoutes.mockResolvedValue([route('c-1a-2b', 'Ulus ve çevresi')])
    renderPage('/explore?sehir=ankara&rotalar=1')
    await userEvent.click(await screen.findByRole('button', { name: /Ulus ve çevresi/ }))
    await userEvent.click(screen.getByRole('button', { name: /Bu rotayı başlat/ }))
    expect(await screen.findByText('Giriş sayfası')).toBeInTheDocument()
    expect(startCommunityRoute).not.toHaveBeenCalled()
  })

  it('starts a favourite route for premium users and opens it', async () => {
    auth = { user: { id: 1 }, hasAccess: true }
    communityRoutes.mockResolvedValue([route('c-9k-11', 'Moda ve çevresi')])
    startCommunityRoute.mockResolvedValue({ id: 77 })
    renderPage('/explore?sehir=istanbul&ilce=kadikoy&rotalar=1')
    await userEvent.click(await screen.findByRole('button', { name: /Moda ve çevresi/ }))
    await userEvent.click(screen.getByRole('button', { name: /Bu rotayı başlat/ }))
    expect(startCommunityRoute).toHaveBeenCalledWith({ city: 'istanbul', district: 'kadikoy', key: 'c-9k-11', date: '2026-09-28' })
    expect(await screen.findByText('Rota sayfası')).toBeInTheDocument()
  })

  it('says so when the area has no favourites yet', async () => {
    renderPage('/explore?sehir=izmir&rotalar=1')
    expect(await screen.findByText('Henüz favori rota yok')).toBeInTheDocument()
  })

  it('says so when the area has no favourite places yet', async () => {
    renderPage('/explore?sehir=izmir&favoriler=1')
    expect(await screen.findByText('Henüz puanlanan mekan yok')).toBeInTheDocument()
  })
})

describe('ExplorePage worship kinds', () => {
  it('shows the kinds under İbadet (cami and mescit together) and filters by one', async () => {
    renderPage('/explore?category=WORSHIP&ilce=uskudar')
    const kinds = await screen.findByRole('region', { name: 'İbadet yeri türü' }).catch(() => null)
    const scope = kinds ? within(kinds) : screen
    await userEvent.click(scope.getByRole('button', { name: 'Kilise' }))

    await waitFor(() => expect(searchPlaces).toHaveBeenLastCalledWith(
      expect.objectContaining({ category: 'WORSHIP', tag: 'church', district: 'uskudar' })))
    expect(search).toContain('tur=church')
    expect(scope.getByRole('button', { name: 'Cami ve mescit' })).toBeInTheDocument()

    // Another category drops the kind
    await userEvent.click(screen.getByRole('button', { name: /Kafe/ }))
    await waitFor(() => expect(search).not.toContain('tur='))
    expect(screen.queryByRole('button', { name: 'Cami ve mescit' })).not.toBeInTheDocument()
  })
})

describe('ExplorePage market kinds', () => {
  it('shows the chains and local shops under Market and filters by one', async () => {
    renderPage('/explore?category=MARKET&ilce=uskudar')
    const kinds = await screen.findByRole('region', { name: 'Market türü' }).catch(() => null)
    const scope = kinds ? within(kinds) : screen
    expect(scope.getByRole('button', { name: 'Bakkal ve diğer' })).toBeInTheDocument()
    await userEvent.click(scope.getByRole('button', { name: 'BİM' }))

    await waitFor(() => expect(searchPlaces).toHaveBeenLastCalledWith(
      expect.objectContaining({ category: 'MARKET', tag: 'bim', district: 'uskudar' })))
    expect(search).toContain('tur=bim')
  })
})

describe('ExplorePage back navigation', () => {
  it('keeps the loaded list and the scroll position after opening a place', async () => {
    renderPage('/explore?category=CAFE&sehir=istanbul&ilce=kadikoy')
    expect(await screen.findByText('Moda Kahve')).toBeInTheDocument()
    const calls = searchPlaces.mock.calls.length
    window.scrollY = 1200
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)

    // Leave for a place and come back to the same list
    cleanup()
    renderPage('/explore?category=CAFE&sehir=istanbul&ilce=kadikoy')

    expect(screen.getByText('Moda Kahve')).toBeInTheDocument()
    expect(scrollTo).toHaveBeenCalledWith(0, 1200)
    // Nothing is loaded again from the first page
    await new Promise(resolve => setTimeout(resolve, 50))
    expect(searchPlaces.mock.calls.length).toBe(calls)
    scrollTo.mockRestore()
  })
})
