import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { City, District, RouteSummary } from '../api/types'
import { resetDistrictCache } from '../lib/districts'
import { RoutesPage } from './RoutesPage'

const city = (slug: string, name: string, placeCount = 1000): City => ({
  slug, name, placeCount, districtCount: 10, latitude: 41, longitude: 29, south: 40.5, west: 28.5, north: 41.5, east: 29.5,
})
const district = (slug: string, name: string): District => ({
  slug, name, placeCount: 100, latitude: 41, longitude: 29, south: 40.9, west: 28.9, north: 41.1, east: 29.1,
})
const CITIES = [city('istanbul', 'İstanbul'), city('izmir', 'İzmir'), city('kocaeli', 'Kocaeli')]
const DISTRICTS = [district('kadikoy', 'Kadıköy'), district('besiktas', 'Beşiktaş')]

const routes = vi.fn()
const planRoute = vi.fn()
const cityAt = vi.fn()
const districts = vi.fn()
vi.mock('../api', () => ({
  api: {
    routes: (...args: unknown[]) => routes(...args),
    planRoute: (...args: unknown[]) => planRoute(...args),
    cityAt: (...args: unknown[]) => cityAt(...args),
    districts: (...args: unknown[]) => districts(...args),
  },
}))
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 1, preferences: { walkingTolerance: 'MEDIUM', defaultPartySize: 2, defaultBudget: null, interests: [] } } }),
}))
vi.mock('../components/gate', () => ({ useGate: () => () => true, Locked: () => null }))
let location = { latitude: 40.76, longitude: 29.92, source: 'gps' as string }
vi.mock('../context/LocationContext', () => ({ useUserLocation: () => location }))
let citySlug = 'istanbul'
vi.mock('../context/CityContext', () => ({
  useCity: () => ({
    city: CITIES.find(c => c.slug === citySlug) ?? null, citySlug, cities: CITIES, loading: false, error: null, reload: () => {}, setCity: vi.fn(),
  }),
}))

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/routes']}>
      <Routes>
        <Route path="/routes" element={<RoutesPage />} />
        <Route path="/routes/:id" element={<p>Rota detayı</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

async function openForm() {
  const user = userEvent.setup()
  renderPage()
  await user.click(await screen.findByRole('button', { name: /Yeni rota/ }))
  return { user, form: screen.getByRole('dialog', { name: 'Yeni rota' }) }
}

beforeEach(() => {
  resetDistrictCache()
  location = { latitude: 40.76, longitude: 29.92, source: 'gps' }
  citySlug = 'istanbul'
  routes.mockReset().mockResolvedValue([])
  planRoute.mockReset().mockResolvedValue({ id: 42 })
  // The user is in Kocaeli, the app city is Istanbul
  cityAt.mockReset().mockResolvedValue(CITIES[2])
  districts.mockReset().mockResolvedValue(DISTRICTS)
})

describe('New route form', () => {
  it('does not submit when walking, stop or interest toggles are clicked', async () => {
    const { user, form } = await openForm()
    await user.click(within(form).getByRole('tab', { name: 'Az yürüyelim' }))
    await user.click(within(form).getByRole('button', { name: /Kahvaltı/ }))
    await user.click(within(form).getByRole('button', { name: 'Deniz' }))
    await user.click(within(form).getByRole('button', { name: 'Arttır' }))

    expect(planRoute).not.toHaveBeenCalled()
    expect(within(form).getByRole('tab', { name: 'Az yürüyelim' })).toHaveAttribute('aria-selected', 'true')
    expect(within(form).getByRole('button', { name: 'Deniz' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByText('Rota detayı')).not.toBeInTheDocument()
  })

  it('does not submit on Enter in the budget field', async () => {
    const { user, form } = await openForm()
    await user.type(within(form).getByPlaceholderText('Sınırsız'), '500{Enter}')
    expect(planRoute).not.toHaveBeenCalled()
  })

  it('starts in the chosen city when the user is in another city, and sends city / district / AREA', async () => {
    const { user, form } = await openForm()
    await waitFor(() => expect(cityAt).toHaveBeenCalled())
    expect(within(form).getByRole('radio', { name: /Şehir \/ ilçe seç/ })).toHaveAttribute('aria-checked', 'true')

    await user.click(within(form).getByRole('button', { name: /İlçe seç/ }))
    await user.click(within(screen.getByRole('dialog', { name: 'İlçe seç' })).getByRole('button', { name: /Kadıköy/ }))
    expect(planRoute).not.toHaveBeenCalled()
    await user.click(within(form).getByRole('button', { name: 'Deniz' }))
    await user.click(within(form).getByRole('button', { name: 'Rotamı oluştur' }))

    await waitFor(() => expect(planRoute).toHaveBeenCalledTimes(1))
    const request = planRoute.mock.calls[0][0]
    expect(request).toMatchObject({ startMode: 'AREA', city: 'istanbul', district: 'kadikoy', interests: ['sea'] })
    expect(request.latitude).toBeUndefined()
    expect(request.longitude).toBeUndefined()
    expect(await screen.findByText('Rota detayı')).toBeInTheDocument()
  })

  it('lets the user pick another city', async () => {
    const { user, form } = await openForm()
    await user.click(within(form).getByRole('button', { name: /Şehir seç/ }))
    await user.click(within(screen.getByRole('dialog', { name: 'Şehir seç' })).getByRole('button', { name: /İzmir/ }))
    await user.click(within(form).getByRole('button', { name: 'Rotamı oluştur' }))
    await waitFor(() => expect(planRoute).toHaveBeenCalledWith(expect.objectContaining({ startMode: 'AREA', city: 'izmir', district: undefined })))
  })

  it('defaults to the location when the user is in the chosen city', async () => {
    cityAt.mockResolvedValue(CITIES[0])
    const { user, form } = await openForm()
    await waitFor(() => expect(within(form).getByRole('radio', { name: 'Konumumdan' })).toHaveAttribute('aria-checked', 'true'))
    await user.click(within(form).getByRole('button', { name: 'Rotamı oluştur' }))
    await waitFor(() => expect(planRoute).toHaveBeenCalledWith(expect.objectContaining({ startMode: 'LOCATION', latitude: 40.76, longitude: 29.92 })))
    expect(planRoute.mock.calls[0][0].city).toBeUndefined()
  })

  it('hides the location option without a real GPS position', async () => {
    location = { latitude: 40.991, longitude: 29.023, source: 'demo-denied' }
    const { form } = await openForm()
    expect(within(form).queryByRole('radio', { name: 'Konumumdan' })).not.toBeInTheDocument()
    expect(within(form).getByRole('radio', { name: /Şehir \/ ilçe seç/ })).toHaveAttribute('aria-checked', 'true')
    expect(cityAt).not.toHaveBeenCalled()
  })

  it('shows a spinner and disables the button while the route is created', async () => {
    let finish: (value: { id: number }) => void = () => {}
    planRoute.mockReturnValue(new Promise(resolve => { finish = resolve }))
    const { user, form } = await openForm()
    await user.click(within(form).getByRole('button', { name: 'Rotamı oluştur' }))

    const button = within(form).getByRole('button', { name: /Rotan hazırlanıyor/ })
    expect(button).toBeDisabled()
    expect(within(button).getByLabelText('Yükleniyor')).toBeInTheDocument()
    await user.click(button)
    expect(planRoute).toHaveBeenCalledTimes(1)

    finish({ id: 7 })
    expect(await screen.findByText('Rota detayı')).toBeInTheDocument()
  })

  it('explains the empty start time', async () => {
    const { form } = await openForm()
    expect(within(form).getByText(/en erken uygun saatten başlar/)).toBeInTheDocument()
  })

  it('asks for a place when no city is known and there is no GPS', async () => {
    location = { latitude: 40.991, longitude: 29.023, source: 'demo-unavailable' }
    citySlug = ''
    const { user, form } = await openForm()
    await user.click(within(form).getByRole('button', { name: 'Rotamı oluştur' }))
    expect(await within(form).findByText(/nereden başlayacağını seç/)).toBeInTheDocument()
    expect(planRoute).not.toHaveBeenCalled()
  })
})

describe('Route list', () => {
  it('shows where a route starts', async () => {
    const summary: RouteSummary = {
      id: 3, title: 'Kadıköy turu', date: '2999-01-01', status: 'DRAFT', saved: false, stopCount: 4,
      totalEstimatedCost: 800, createdAt: '2026-09-28T10:00:00Z', startLabel: 'Kadıköy merkezi',
    }
    routes.mockResolvedValue([summary])
    renderPage()
    expect(await screen.findByText(/Başlangıç: Kadıköy merkezi/)).toBeInTheDocument()
  })
})
