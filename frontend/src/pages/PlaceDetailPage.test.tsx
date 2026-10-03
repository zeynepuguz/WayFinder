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

  // Google opens its own listing of the place (and says when it is closed): "<city> <name>", not our pin
  it('searches Google Maps for the city and the name of the place', async () => {
    current = place(8, 'Akdağ Çayevi', { source: 'OSM', district: 'Suluova', city: 'Amasya',
      latitude: 40.822, longitude: 35.655 })
    renderPage()
    await screen.findByRole('heading', { name: 'Akdağ Çayevi' })
    const href = screen.getByRole('link', { name: /Google Haritalar’da aç/ }).getAttribute('href')
    expect(href).toContain('/maps/search/?api=1&query=Amasya%20Akda%C4%9F%20%C3%87ayevi')
    expect(href).not.toContain('40.822')
  })

  it('never links a website that is not http(s)', async () => {
    current = place(7, 'Köşe Kahve', { source: 'OVERTURE', website: 'javascript:alert(1)' })
    renderPage()
    await screen.findByRole('heading', { name: 'Köşe Kahve' })
    expect(screen.queryByRole('link', { name: /Web sitesi/ })).not.toBeInTheDocument()
  })
})
