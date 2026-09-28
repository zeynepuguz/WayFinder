import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { HomeResponse, RouteSummary } from '../api/types'
import { todayIso } from '../lib/format'
import { HomePage } from './HomePage'

const home = vi.fn()
vi.mock('../api', () => ({ api: { home: (...args: unknown[]) => home(...args) } }))
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 1, displayName: 'Zeynep Uğuz' }, hasAccess: true }),
}))
vi.mock('../context/CityContext', () => ({ useCity: () => ({ city: null, citySlug: null }) }))
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

beforeEach(() => vi.clearAllMocks())

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
