import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { Place } from '../api/types'
import { PHOTO, place } from '../test/fixtures'
import { PlaceDetailPage } from './PlaceDetailPage'

let current: Place
vi.mock('../api', () => ({ api: { place: () => Promise.resolve(current), photos: () => Promise.resolve([]) } }))
vi.mock('../components/RouteMap', () => ({ RouteMap: () => null }))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: null, hasAccess: false }) }))
vi.mock('../context/SavedPlacesContext', () => ({ useSavedPlaces: () => ({ isSaved: () => false, toggle: vi.fn() }) }))

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/places/5']}>
      <Routes><Route path="/places/:id" element={<PlaceDetailPage />} /></Routes>
    </MemoryRouter>,
  )
}

describe('PlaceDetailPage photo', () => {
  it('shows the photo as hero with its credit, and drops both if it fails', async () => {
    current = place(5, 'Haydarpaşa Garı', { category: 'ATTRACTION', image: PHOTO })
    renderPage()
    const img = await screen.findByRole('img', { name: 'Haydarpaşa Garı' })
    expect(img).toHaveAttribute('src', PHOTO.url)
    expect(screen.getByRole('link', { name: /Fotoğraf: Jane Doe · CC BY-SA 4.0 · Wikimedia Commons/ })).toBeInTheDocument()

    fireEvent.error(img)
    expect(screen.queryByRole('img', { name: 'Haydarpaşa Garı' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Wikimedia Commons/)).not.toBeInTheDocument()
  })

  it('keeps the category hero without a photo', async () => {
    current = place(5, 'Köşe Kahve')
    renderPage()
    await screen.findByRole('heading', { name: 'Köşe Kahve' })
    expect(screen.queryByRole('img', { name: 'Köşe Kahve' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Wikimedia Commons/)).not.toBeInTheDocument()
  })
})
