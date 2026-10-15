import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route as RouterRoute, Routes } from 'react-router'
import type { Route } from '../api/types'
import { RouteDetailPage } from './RouteDetailPage'

const route = vi.fn()
const sendMessage = vi.fn()
const updateRoute = vi.fn()
vi.mock('../api', () => ({
  api: {
    route: (...args: unknown[]) => route(...args),
    sendMessage: (...args: unknown[]) => sendMessage(...args),
    updateRoute: (...args: unknown[]) => updateRoute(...args),
  },
}))
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

  it('“Rotanda değişiklik yapmak ister misin?”: the assistant changes this route from the request written here', async () => {
    route.mockResolvedValue(ROUTE)
    sendMessage.mockResolvedValue({
      reply: 'Rotanı güncelledim: bir müze ekledim.', intent: { type: 'REPLAN', source: 'AI' },
      route: { ...ROUTE, title: 'Müzeli gün' }, recommendations: [], changes: ['Müze eklendi'], conversationId: 12,
    })
    renderPage()
    await userEvent.type(await screen.findByRole('textbox', { name: 'Rotanda değişiklik isteğin' }), 'bir müze ekle')
    await userEvent.click(screen.getByRole('button', { name: 'Gönder' }))

    // the route's id goes along, no chat id: the backend continues the chat about this route
    expect(sendMessage).toHaveBeenCalledWith('bir müze ekle', 40.99, 29.02, null, 5)
    expect(await screen.findByText('Rotanı güncelledim: bir müze ekledim.')).toBeInTheDocument()
    expect(screen.getByText('Müzeli gün')).toBeInTheDocument()
    expect(screen.getByText('Müze eklendi')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Sohbette devam et/ })).toHaveAttribute('href', '/assistant?c=12')
  })

  it('offers to add an assistant’s route that is not in Rotalarım yet', async () => {
    route.mockResolvedValue({ ...ROUTE, listed: false })
    updateRoute.mockResolvedValue({ ...ROUTE, listed: true })
    renderPage()
    expect(await screen.findByText('Bu rota henüz Rotalarım’da değil.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Ekle' }))
    expect(updateRoute).toHaveBeenCalledWith(5, { listed: true })
    expect(screen.queryByText('Bu rota henüz Rotalarım’da değil.')).not.toBeInTheDocument()
  })
})
