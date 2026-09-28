import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { City, District, Page, Place, PopularRoute } from '../api/types'
import { resetDistrictCache } from '../lib/districts'
import { place } from '../test/fixtures'
import { ExplorePage } from './ExplorePage'

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
vi.mock('../api', () => ({
  api: {
    districts: (...args: unknown[]) => districts(...args),
    searchPlaces: (...args: unknown[]) => searchPlaces(...args),
    nearbyPlaces: (...args: unknown[]) => nearbyPlaces(...args),
    popularRoutes: (...args: unknown[]) => popularRoutes(...args),
    startPopularRoute: (...args: unknown[]) => startPopularRoute(...args),
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
  resetDistrictCache()
  districts.mockReset().mockImplementation((slug: string) => Promise.resolve(slug === 'ankara' ? ANKARA_DISTRICTS : DISTRICTS))
  popularRoutes.mockReset().mockResolvedValue([])
  startPopularRoute.mockReset()
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

const route = (theme: PopularRoute['theme'], title: string): PopularRoute => ({
  theme, title, description: 'Kalenin çevresinde tarih', startLabel: 'Ulus Meydanı', startLatitude: 39.94, startLongitude: 32.85,
  date: '2026-09-28', totalWalkingMinutes: 42, estimatedCostPerPerson: 150, unknownPriceStops: 1,
  stops: [
    { time: '10:00', type: 'SIGHTSEEING', typeLabel: 'Gezi', walkingMinutes: 5, distanceMeters: 400,
      place: { id: 11, name: 'Ankara Kalesi', category: 'ATTRACTION', latitude: 39.94, longitude: 32.86, image: null,
        estimatedCost: 0, verified: false, district: 'Altındağ' } },
    { time: '11:30', type: 'LUNCH', typeLabel: 'Öğle yemeği', walkingMinutes: 8, distanceMeters: 600,
      place: { id: 12, name: 'Kale Lokantası', category: 'RESTAURANT', latitude: 39.93, longitude: 32.85, image: null,
        estimatedCost: null, verified: false, district: 'Altındağ' } },
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

describe('ExplorePage popular routes', () => {
  it('shows the ready-made routes of the city instead of the place list', async () => {
    popularRoutes.mockResolvedValue([route('HISTORY', 'Tarihi Ankara')])
    renderPage('/explore?sehir=ankara&category=CAFE')
    await userEvent.click(await screen.findByRole('button', { name: /Popüler rotalar/ }))

    expect(search).toContain('rotalar=1')
    expect(screen.getByRole('button', { name: /Popüler rotalar/ })).toHaveAttribute('aria-pressed', 'true')
    expect(await screen.findByText('Tarihi Ankara')).toBeInTheDocument()
    expect(popularRoutes).toHaveBeenCalledWith('ankara', undefined)
    // categories and the place tabs are about places
    expect(screen.queryByRole('button', { name: 'Tümü' })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Yakınımda' })).not.toBeInTheDocument()

    expect(screen.getByText('2 durak')).toBeInTheDocument()
    expect(screen.getByText(/42 dk yürüyüş/)).toBeInTheDocument()
    expect(screen.getByText(/Kişi başı ~150 TL · 1 durağın fiyatı bilinmiyor/)).toBeInTheDocument()
    expect(screen.getByText('11:30 · Öğle yemeği')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ankara Kalesi' })).toHaveAttribute('href', '/places/11')

    // the map and the start button appear once the card is opened
    expect(screen.queryByTestId('route-map')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Tarihi Ankara/ }))
    expect(screen.getByTestId('route-map')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Tarihi Ankara/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('sends guests to login before starting a route', async () => {
    popularRoutes.mockResolvedValue([route('HISTORY', 'Tarihi Ankara')])
    renderPage('/explore?sehir=ankara&rotalar=1')
    await userEvent.click(await screen.findByRole('button', { name: /Tarihi Ankara/ }))
    await userEvent.click(screen.getByRole('button', { name: /Bu rotayı başlat/ }))
    expect(await screen.findByText('Giriş sayfası')).toBeInTheDocument()
    expect(startPopularRoute).not.toHaveBeenCalled()
  })

  it('starts the route for premium users and opens it', async () => {
    auth = { user: { id: 1 }, hasAccess: true }
    popularRoutes.mockResolvedValue([route('FOOD', 'Lezzet Turu')])
    startPopularRoute.mockResolvedValue({ id: 77 })
    renderPage('/explore?sehir=istanbul&ilce=kadikoy&rotalar=1')
    await userEvent.click(await screen.findByRole('button', { name: /Lezzet Turu/ }))
    expect(popularRoutes).toHaveBeenLastCalledWith('istanbul', 'kadikoy')
    await userEvent.click(screen.getByRole('button', { name: /Bu rotayı başlat/ }))
    expect(startPopularRoute).toHaveBeenCalledWith({ city: 'istanbul', district: 'kadikoy', theme: 'FOOD' })
    expect(await screen.findByText('Rota sayfası')).toBeInTheDocument()
  })

  it('says so when the area has no routes yet', async () => {
    renderPage('/explore?sehir=izmir&rotalar=1')
    expect(await screen.findByText('Bu bölge için henüz yeterli mekan yok')).toBeInTheDocument()
  })
})
