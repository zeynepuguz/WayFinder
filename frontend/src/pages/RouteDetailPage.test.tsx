import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route as RouterRoute, Routes } from 'react-router'
import type { Route } from '../api/types'
import { RouteDetailPage } from './RouteDetailPage'

const route = vi.fn()
vi.mock('../api', () => ({ api: { route: (...args: unknown[]) => route(...args) } }))
vi.mock('../components/gate', () => ({ useGate: () => () => true }))
vi.mock('../components/RouteMap', () => ({ RouteMap: () => <div data-testid="route-map" /> }))
vi.mock('../context/LocationContext', () => ({ useUserLocation: () => ({ latitude: 40.99, longitude: 29.02, source: 'gps' }) }))

const ROUTE: Route = {
  id: 5, title: 'Kadıköy’de bir gün', date: '2999-01-01', status: 'DRAFT', saved: false,
  startLatitude: 40.99, startLongitude: 29.02, startTime: '10:00', endTime: '18:00', partySize: 2, budget: null,
  totalEstimatedCost: 900, totalWalkingMeters: 2000, totalWalkingMinutes: 25, walkingTolerance: 'LOW',
  interests: ['sea', 'nature', 'budget'], weather: { condition: null, temperature: null, advice: null },
  notes: ['Sanat ilgine uyan açık bir yer bulunamadı.'], stops: [], createdAt: '2026-09-28T10:00:00Z', updatedAt: '2026-09-28T10:00:00Z',
  startLabel: 'Kadıköy merkezi',
}

function renderPage(state?: unknown) {
  render(
    <MemoryRouter initialEntries={[{ pathname: '/routes/5', state }]}>
      <Routes>
        <RouterRoute path="/routes/:id" element={<RouteDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('RouteDetailPage', () => {
  it('shows the start, the chosen interests and the notes about them', async () => {
    route.mockResolvedValue(ROUTE)
    renderPage()
    expect(await screen.findByText(/Başlangıç: Kadıköy merkezi/)).toBeInTheDocument()
    expect(screen.getByText('Deniz')).toBeInTheDocument()
    expect(screen.getByText('Doğa')).toBeInTheDocument()
    expect(screen.getByText('Uygun fiyat')).toBeInTheDocument()
    expect(screen.getByText(/Sanat ilgine uyan/)).toBeInTheDocument()
  })

  it('falls back to the interests chosen in the form when the route has none', async () => {
    route.mockResolvedValue({ ...ROUTE, interests: [], startLabel: null })
    renderPage({ interests: ['history'] })
    expect(await screen.findByText('Tarih')).toBeInTheDocument()
    expect(screen.queryByText(/Başlangıç:/)).not.toBeInTheDocument()
  })
})
