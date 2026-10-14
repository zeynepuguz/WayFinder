import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { PlaceRating as Rating } from '../api/types'
import { shownRating } from '../lib/format'
import { PlaceRating } from './PlaceRating'
import { ToastProvider } from './ui'

const placeRating = vi.fn()
const ratePlace = vi.fn()
const removePlaceRating = vi.fn()
const setRatingReasons = vi.fn()
let user: { id: number } | null = { id: 1 }

vi.mock('../api', () => ({
  api: {
    placeRating: (id: number) => placeRating(id),
    ratePlace: (id: number, stars: number) => ratePlace(id, stars),
    removePlaceRating: (id: number) => removePlaceRating(id),
    setRatingReasons: (id: number, reasons: string[]) => setRatingReasons(id, reasons),
  },
}))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user }) }))

const rating = (changes: Partial<Rating> = {}): Rating =>
  ({ average: null, count: 0, mine: null, favoriteCount: 0, rateable: true, myReasons: [], topReasons: [], ...changes })

function renderRating() {
  return render(
    <MemoryRouter initialEntries={['/places/7']}>
      <ToastProvider>
        <Routes>
          <Route path="/places/:id" element={<PlaceRating placeId={7} />} />
          <Route path="/login" element={<h1>Login</h1>} />
        </Routes>
      </ToastProvider>
    </MemoryRouter>,
  )
}

describe('PlaceRating', () => {
  beforeEach(() => {
    user = { id: 1 }
    placeRating.mockReset()
    ratePlace.mockReset()
    removePlaceRating.mockReset()
    setRatingReasons.mockReset()
  })

  it('saves the stars, then asks what the visitor liked', async () => {
    placeRating.mockResolvedValue(rating({ average: 4.6, count: 12 }))
    ratePlace.mockResolvedValue(rating({ average: 4.5, count: 13, mine: 4 }))
    setRatingReasons.mockResolvedValue(rating({ average: 4.5, count: 13, mine: 4, myReasons: ['AFFORDABLE', 'VIEW'] }))
    renderRating()

    expect(await screen.findByText('4.6 · 12 oy')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: '4 yıldız' }))

    expect(ratePlace).toHaveBeenCalledWith(7, 4)
    expect(await screen.findByText('Neyi beğendin?', { exact: false })).toBeInTheDocument()
    // Four stars: only liked answers are offered
    expect(screen.queryByRole('button', { name: 'Pahalı' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Uygun fiyatlı' }))
    await userEvent.click(screen.getByRole('button', { name: 'Manzarası güzel' }))
    await userEvent.click(screen.getByRole('button', { name: 'Gönder' }))

    expect(setRatingReasons).toHaveBeenCalledWith(7, ['AFFORDABLE', 'VIEW'])
    expect(await screen.findByText(/Sen 4 yıldız verdin: Uygun fiyatlı, Manzarası güzel/)).toBeInTheDocument()
  })

  it('offers the disliked answers for a low rating and can be skipped', async () => {
    placeRating.mockResolvedValue(rating())
    ratePlace.mockResolvedValue(rating({ average: 1, count: 1, mine: 1 }))
    renderRating()

    await userEvent.click(await screen.findByRole('radio', { name: '1 yıldız' }))

    expect(await screen.findByRole('button', { name: 'Pahalı' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Lezzetli' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Geç' }))

    expect(setRatingReasons).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Nedenini söyle' })).toBeInTheDocument()
  })

  it('shows what most raters said', async () => {
    placeRating.mockResolvedValue(rating({ average: 4.2, count: 9, topReasons: [
      { reason: 'AFFORDABLE', count: 5, positive: true }, { reason: 'CROWDED_NOISY', count: 2, positive: false },
    ] }))
    renderRating()

    expect(await screen.findByText(/Uygun fiyatlı · 5/)).toBeInTheDocument()
    expect(screen.getByText(/Kalabalık, gürültülü · 2/)).toBeInTheDocument()
  })

  it('takes the rating back when the same star is tapped again', async () => {
    placeRating.mockResolvedValue(rating({ average: 5, count: 1, mine: 5 }))
    removePlaceRating.mockResolvedValue(rating())
    renderRating()

    await userEvent.click(await screen.findByRole('radio', { name: '5 yıldız' }))

    expect(removePlaceRating).toHaveBeenCalledWith(7)
    expect(await screen.findByText('Henüz oy yok')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Gönder' })).toBeNull()
  })

  it('sends a guest to sign in instead of rating', async () => {
    user = null
    placeRating.mockResolvedValue(rating())
    renderRating()

    await userEvent.click(await screen.findByRole('radio', { name: '5 yıldız' }))

    expect(ratePlace).not.toHaveBeenCalled()
    expect(await screen.findByRole('heading', { name: 'Login' })).toBeInTheDocument()
  })

  it('shows nothing for places that are not rated (sights, markets)', async () => {
    placeRating.mockResolvedValue(rating({ rateable: false }))
    const { container } = renderRating()

    await vi.waitFor(() => expect(placeRating).toHaveBeenCalled())
    expect(container.querySelector('.star-input')).toBeNull()
  })
})

describe('shownRating', () => {
  it('uses the users’ average only once enough of them voted', () => {
    expect(shownRating({ rating: 4.2, userRating: 5, userRatingCount: 1 })).toBe(4.2)
    expect(shownRating({ rating: null, userRating: 5, userRatingCount: 2 })).toBeNull()
    expect(shownRating({ rating: 4.2, userRating: 4.7, userRatingCount: 3 })).toBe(4.7)
    expect(shownRating({ rating: 4.2 })).toBe(4.2)
  })
})
