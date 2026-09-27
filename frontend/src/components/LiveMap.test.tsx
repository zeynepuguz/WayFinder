import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import type { Place, Recommendation } from '../api/types'
import { LiveMap } from './LiveMap'

const place = (id: number, name: string, extra: Partial<Place> = {}): Place => ({
  id, name, description: null, address: null, neighborhood: null, latitude: 40.99, longitude: 29.02,
  category: 'CAFE', estimatedCost: null, rating: null, indoor: false, avgVisitMinutes: null, tags: [],
  openingHours: [], openNow: null, source: 'OSM', lastVerifiedAt: null, verified: false,
  sourceUrl: 'https://www.openstreetmap.org/node/1', distanceMeters: 120, ...extra,
})
const rec = (p: Place, reasons: string[], whyBetter: string | null): Recommendation =>
  ({ place: p, type: 'COFFEE', score: 1, reasons, whyBetter })

const placesInArea = vi.fn()
const tieredRecommendations = vi.fn()
vi.mock('../api', () => ({ api: { placesInArea: (...args: unknown[]) => placesInArea(...args), tieredRecommendations: (...args: unknown[]) => tieredRecommendations(...args) } }))
vi.mock('../context/LocationContext', () => ({
  useUserLocation: () => ({ latitude: 40.991, longitude: 29.023, source: 'gps', refresh: () => {} }),
  useLiveLocation: () => ({ latitude: null, longitude: null, accuracy: null, status: 'denied' }),
  locationLabel: () => 'Konum izni yok',
}))

describe('LiveMap', () => {
  it('loads places for the visible area and shows both recommendation tiers', async () => {
    placesInArea.mockResolvedValue([place(1, 'Köşe Kahve')])
    tieredRecommendations.mockResolvedValue({
      nearby: [rec(place(2, 'Yakın Kafe', { estimatedCost: 0 }), ['Şu an açık'], null)],
      farther: [rec(place(3, 'Uzak Müze', { category: 'MUSEUM' }), ['1,8 km uzakta (yürüyerek ~23 dk)'], 'Yağmurda kapalı alan')],
    })

    render(<MemoryRouter><LiveMap category="CAFE" /></MemoryRouter>)

    await waitFor(() => expect(placesInArea).toHaveBeenCalled())
    const [box, options] = placesInArea.mock.calls[0]
    expect(box.north - box.south).toBeLessThanOrEqual(0.6)
    expect(options).toMatchObject({ lat: 40.991, lon: 29.023, category: 'CAFE', limit: 300 })

    expect(await screen.findByText('Yakın Kafe')).toBeInTheDocument()
    expect(screen.getByText('Daha uygun ama sana yakın değil')).toBeInTheDocument()
    expect(screen.getByText('1,8 km uzakta (yürüyerek ~23 dk)')).toBeInTheDocument()
    expect(screen.getByText('Yağmurda kapalı alan')).toBeInTheDocument()

    // choosing a suggestion opens its card with honest unknowns
    await userEvent.click(screen.getByText('Uzak Müze'))
    const card = await screen.findByRole('dialog', { name: 'Uzak Müze' })
    expect(card).toHaveTextContent('Fiyat bilgisi yok')
    expect(card).toHaveTextContent('Saat bilgisi yok')
    expect(screen.getByRole('link', { name: 'Mekanı gör' })).toHaveAttribute('href', '/places/3')
  })
})
