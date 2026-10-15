import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { City, District, HomeResponse, RouteSummary } from '../api/types'
import { resetDistrictCache } from '../lib/districts'
import { todayIso } from '../lib/format'
import { setHomeArea } from '../lib/homeArea'
import { HomePage } from './HomePage'

const city = (slug: string, name: string, latitude: number, longitude: number): City => ({
  slug, name, placeCount: 1000, districtCount: 12, latitude, longitude, south: 0, west: 0, north: 0, east: 0,
})
const ISTANBUL = city('istanbul', 'İstanbul', 41.01, 28.97)
const KOCAELI = city('kocaeli', 'Kocaeli', 40.77, 29.94)
const IZMIT: District = { slug: 'izmit', name: 'İzmit', placeCount: 400, latitude: 40.76, longitude: 29.92, south: 0, west: 0, north: 0, east: 0 }

const home = vi.fn()
const cityAt = vi.fn()
const districts = vi.fn()
vi.mock('../api', () => ({
  api: {
    home: (...args: unknown[]) => home(...args),
    cityAt: (...args: unknown[]) => cityAt(...args),
    districts: (...args: unknown[]) => districts(...args),
  },
}))
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 1, displayName: 'Zeynep Uğuz' }, hasAccess: true }),
}))
// Keşfet's choice (Ankara here) must not change Ana Sayfa
vi.mock('../context/CityContext', () => ({
  useCity: () => ({ city: null, citySlug: 'ankara', cities: [ISTANBUL, KOCAELI], loading: false, error: null, reload: () => {}, setCity: vi.fn() }),
}))
vi.mock('../context/SavedPlacesContext', () => ({ useSavedPlaces: () => ({ isSaved: () => false, toggle: vi.fn() }) }))
vi.mock('../context/LocationContext', () => ({
  useUserLocation: () => ({ latitude: 40.991, longitude: 29.023, source: 'gps', refresh: () => {} }),
  locationLabel: () => 'Konumun',
}))

const shift = (days: number) => {
  const date = new Date(`${todayIso()}T12:00:00`)
  date.setDate(date.getDate() + days)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

const summary = (id: number, title: string, date: string, status: RouteSummary['status'] = 'DRAFT'): RouteSummary =>
  ({ id, title, date, status, saved: false, stopCount: 5, totalEstimatedCost: 600, createdAt: '2026-09-27T10:00:00Z' } as RouteSummary)

const response = (extra: Partial<HomeResponse>): HomeResponse => ({
  weather: null, suggestedStopType: 'COFFEE', suggestions: [], currentRoute: null, tomorrowRoute: null, prompts: [], ...extra,
})

function CurrentUrl() {
  const location = useLocation()
  return <output data-testid="url">{location.pathname + location.search}</output>
}

function renderHome() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="*" element={<CurrentUrl />} />
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  setHomeArea(null)
  resetDistrictCache()
  cityAt.mockResolvedValue(ISTANBUL)
  districts.mockResolvedValue([IZMIT])
})

describe('HomePage route card', () => {
  it('shows today’s route as the active route', async () => {
    home.mockResolvedValue(response({ currentRoute: summary(1, '28 Eylül Pazartesi Rotası', todayIso()) }))
    renderHome()
    expect(await screen.findByText('Aktif rotan')).toBeInTheDocument()
    expect(screen.getByText('28 Eylül Pazartesi Rotası')).toBeInTheDocument()
  })

  it('never shows a route of a past day as active', async () => {
    home.mockResolvedValue(response({ currentRoute: summary(1, '27 Eylül Pazar Rotası', shift(-1)) }))
    renderHome()
    await screen.findByText('Rota fikirleri')
    await Promise.resolve()
    expect(screen.queryByText('Aktif rotan')).not.toBeInTheDocument()
    expect(screen.queryByText('27 Eylül Pazar Rotası')).not.toBeInTheDocument()
  })

  it('shows tomorrow’s route as “Yarınki rotan”', async () => {
    home.mockResolvedValue(response({ tomorrowRoute: summary(2, 'Yarının rotası', shift(1)) }))
    renderHome()
    expect(await screen.findByText('Yarınki rotan')).toBeInTheDocument()
    expect(screen.queryByText('Aktif rotan')).not.toBeInTheDocument()
  })
})

describe('HomePage asks the assistant in a new chat', () => {
  it('“Bugün ne yapmak istersin?” opens an empty new chat, not the last one', async () => {
    home.mockResolvedValue(response({}))
    renderHome()
    await userEvent.click(screen.getByRole('button', { name: /Bugün ne yapmak istersin\?/ }))
    expect(screen.getByTestId('url')).toHaveTextContent(/^\/assistant$/)
  })

  it('an idea card starts a new chat with its prompt', async () => {
    home.mockResolvedValue(response({}))
    renderHome()
    await userEvent.click(screen.getByRole('button', { name: /Kahve & Tatlı Turu/ }))
    const url = screen.getByTestId('url').textContent ?? ''
    expect(url).toMatch(/^\/assistant\?new=1&q=/)
    expect(new URLSearchParams(url.split('?')[1]).get('q')).toBe('Kahve ve tatlı ağırlıklı, az yürümeli bir rota planla')
  })
})

describe('HomePage place', () => {
  it('starts at the device location; Keşfet’s city does not change it', async () => {
    home.mockResolvedValue(response({}))
    renderHome()
    expect(await screen.findByText('İstanbul')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Konumun/ })).toBeInTheDocument()
    expect(home).toHaveBeenCalledWith(40.991, 29.023)
    expect(screen.queryByText(/Moda/)).not.toBeInTheDocument()
  })

  it('a picked district: suggestions, title and route ideas are for it; “Konumumu kullan” goes back', async () => {
    home.mockResolvedValue(response({}))
    renderHome()
    await userEvent.click(screen.getByRole('button', { name: /Yer seç/ }))
    await userEvent.click(within(screen.getByRole('dialog', { name: 'Şehir seç' })).getByRole('button', { name: /Kocaeli/ }))
    const districtSheet = await screen.findByRole('dialog', { name: 'İlçe seç' })
    // the whole city is the first choice
    expect(within(districtSheet).getByRole('button', { name: /Genel – tüm Kocaeli/ })).toBeInTheDocument()
    await userEvent.click(await within(districtSheet).findByRole('button', { name: /İzmit/ }))

    expect(await screen.findAllByText('İzmit, Kocaeli')).not.toHaveLength(0)
    expect(home).toHaveBeenLastCalledWith(40.76, 29.92)
    await userEvent.click(screen.getByRole('button', { name: /Kahve & Tatlı Turu/ }))
    const url = screen.getByTestId('url').textContent ?? ''
    expect(new URLSearchParams(url.split('?')[1]).get('q')).toBe('Kocaeli İzmit’te kahve ve tatlı ağırlıklı, az yürümeli bir rota planla')
  })

  it('“Genel” is the whole city, and the popular routes link opens that city', async () => {
    home.mockResolvedValue(response({}))
    setHomeArea({ city: KOCAELI, district: null })
    renderHome()
    expect(home).toHaveBeenCalledWith(40.77, 29.94)
    await userEvent.click(screen.getByRole('link', { name: /Popüler rotalar/ }))
    expect(screen.getByTestId('url')).toHaveTextContent('/explore?sehir=kocaeli&rotalar=1')
  })

  it('“Konumumu kullan” returns to the device location', async () => {
    home.mockResolvedValue(response({}))
    setHomeArea({ city: KOCAELI, district: IZMIT })
    renderHome()
    await userEvent.click(screen.getByRole('button', { name: /Konumumu kullan/ }))
    expect(home).toHaveBeenLastCalledWith(40.991, 29.023)
    expect(screen.getByRole('button', { name: /Yer seç/ })).toBeInTheDocument()
  })

  it('“Uygun Bütçeli Gün” is shown last as coming soon and asks nothing', async () => {
    home.mockResolvedValue(response({}))
    renderHome()
    const soon = screen.getByText('Uygun Bütçeli Gün').closest('[aria-disabled="true"]')
    expect(soon).not.toBeNull()
    expect(within(soon as HTMLElement).getByText('Yakında')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Uygun Bütçeli Gün/ })).not.toBeInTheDocument()
  })
})
