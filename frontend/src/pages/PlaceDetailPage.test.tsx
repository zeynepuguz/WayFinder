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

describe('PlaceDetailPage Overture place', () => {
  it('shows the phone, website and the Overture attribution', async () => {
    current = place(6, 'Devran Kebap', {
      category: 'RESTAURANT', source: 'OVERTURE', phone: '+90 262 000 00 00', website: 'https://example.com',
    })
    renderPage()
    await screen.findByRole('heading', { name: 'Devran Kebap' })
    expect(screen.getByRole('link', { name: '+90 262 000 00 00' })).toHaveAttribute('href', 'tel:+902620000000')
    expect(screen.getByRole('link', { name: /Web sitesi/ })).toHaveAttribute('href', 'https://example.com')
    expect(screen.getByText(/Overture Maps’ten \(Foursquare, Meta/)).toBeInTheDocument()
    expect(screen.queryByText(/OpenStreetMap katkıcılarından/)).not.toBeInTheDocument()
  })

  it('finds an Overture place by name for directions, an OSM place by its point', async () => {
    current = place(8, 'Devran Kebap', { source: 'OVERTURE', district: 'Çayırova', city: 'Kocaeli' })
    renderPage()
    await screen.findByRole('heading', { name: 'Devran Kebap' })
    expect(screen.getByRole('link', { name: /Yol tarifi al/ }).getAttribute('href'))
      .toContain('destination=Devran%20Kebap%2C%20%C3%87ay%C4%B1rova%2C%20Kocaeli')
  })

  it('never links a website that is not http(s)', async () => {
    current = place(7, 'Köşe Kahve', { source: 'OVERTURE', website: 'javascript:alert(1)' })
    renderPage()
    await screen.findByRole('heading', { name: 'Köşe Kahve' })
    expect(screen.queryByRole('link', { name: /Web sitesi/ })).not.toBeInTheDocument()
  })
})
