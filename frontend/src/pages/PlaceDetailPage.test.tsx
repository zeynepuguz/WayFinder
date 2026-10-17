import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { Place } from '../api/types'
import { PHOTO, place } from '../test/fixtures'
import { PlaceDetailPage, placeArea } from './PlaceDetailPage'

let current: Place
// What the backend says about the place on Google (null = no answer)
let availability: object | null = null
vi.mock('../api', () => ({ api: { place: () => Promise.resolve(current), photos: () => Promise.resolve([]),
  placeAvailability: () => (availability ? Promise.resolve(availability) : Promise.reject(new Error('offline'))),
  placeAddress: () => Promise.resolve({ address: lookedUpAddress }),
  reviewPlace: (...args: unknown[]) => reviewPlace(...args) } }))
const reviewPlace = vi.fn()
// The full address the backend found on OpenStreetMap (null = none)
let lookedUpAddress: string | null = null
vi.mock('../components/RouteMap', () => ({ RouteMap: () => null }))
// null = a guest; { role: 'ADMIN' } = the owner
let authUser: { id: number; role: string } | null = null
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: authUser, hasAccess: false }) }))
vi.mock('../context/SavedPlacesContext', () => ({ useSavedPlaces: () => ({ isSaved: () => false, toggle: vi.fn() }) }))

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/places/5']}>
      <Routes><Route path="/places/:id" element={<PlaceDetailPage />} /></Routes>
    </MemoryRouter>,
  )
}

describe('PlaceDetailPage address', () => {
  beforeEach(() => { lookedUpAddress = null })

  it('without a street address, the full address looked up on OpenStreetMap', async () => {
    lookedUpAddress = 'Dere Mah., Taşhan Sokak, 05100 Merkez/Amasya'
    current = place(5, 'Burmalı Minare Camii', { category: 'ATTRACTION', district: 'Amasya Merkez', city: 'Amasya' })
    renderPage()
    expect(await screen.findByText('Dere Mah., Taşhan Sokak, 05100 Merkez/Amasya')).toBeInTheDocument()
    expect(screen.queryByText('Amasya Merkez, Amasya')).not.toBeInTheDocument()
  })

  it('when none is known, the district and city are the address', async () => {
    current = place(5, 'Burmalı Minare Camii', { category: 'ATTRACTION', district: 'Amasya Merkez', city: 'Amasya' })
    renderPage()
    expect(await screen.findByText('Amasya Merkez, Amasya')).toBeInTheDocument()
  })

  it('a street address is shown as it is', async () => {
    current = place(5, 'Köşe Kahve', { address: 'Caferağa Mah. Moda Cad. 12', district: 'Kadıköy', city: 'İstanbul' })
    renderPage()
    expect(await screen.findByText('Caferağa Mah. Moda Cad. 12')).toBeInTheDocument()
    expect(screen.queryByText('Kadıköy, İstanbul')).not.toBeInTheDocument()
  })

  it('neighbourhood, district, city, a repeated name once', () => {
    expect(placeArea({ neighborhood: 'Moda', district: 'Kadıköy', city: 'İstanbul' })).toBe('Moda, Kadıköy, İstanbul')
    expect(placeArea({ neighborhood: 'Kadıköy', district: 'Kadıköy', city: 'İstanbul' })).toBe('Kadıköy, İstanbul')
    expect(placeArea({ neighborhood: null, district: null, city: null })).toBeNull()
  })
})

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

  it('a tap on the photo opens it full screen with its credit', async () => {
    current = place(5, 'Haydarpaşa Garı', { category: 'ATTRACTION', image: PHOTO })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Fotoğrafı büyüt' }))
    const viewer = screen.getByRole('dialog', { name: 'Fotoğraf' })
    expect(within(viewer).getByRole('img', { name: 'Haydarpaşa Garı' })).toHaveAttribute('src', PHOTO.url)
    expect(within(viewer).getByText(/Jane Doe/)).toBeInTheDocument()

    fireEvent.click(within(viewer).getByRole('button', { name: 'Kapat' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
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

  // "<city> <name>" around our pin: Google opens its own listing (and says when it is closed), the nearest namesake first
  it('searches Google Maps for the city and the name around the pin', async () => {
    availability = null
    current = place(8, 'Akdağ Çayevi', { source: 'OSM', district: 'Suluova', city: 'Amasya',
      latitude: 40.822, longitude: 35.655 })
    renderPage()
    await screen.findByRole('heading', { name: 'Akdağ Çayevi' })
    const href = screen.getByRole('link', { name: /Google Haritalar’da aç/ }).getAttribute('href')
    expect(href).toContain('/maps/search/Amasya%20Akda%C4%9F%20%C3%87ayevi/@40.822,35.655,17z')
  })

  it('opens the exact Google place, says when it closed for good and when nothing matches', async () => {
    current = place(9, 'Seyir Cafe', { source: 'OSM', city: 'Amasya', latitude: 40.647, longitude: 35.82 })
    availability = { status: 'OPEN', mapsUrl: 'https://www.google.com/maps/search/?api=1&query=Seyir&query_place_id=ChIJx', googleName: 'Seyir' }
    renderPage()
    await waitFor(() => expect(screen.getByRole('link', { name: /Google Haritalar’da aç/ }).getAttribute('href'))
      .toContain('query_place_id=ChIJx'))
    cleanup()

    availability = { status: 'CLOSED_PERMANENTLY', mapsUrl: 'x', googleName: 'Seyir Cafe' }
    renderPage()
    expect(await screen.findByText(/kalıcı olarak kapanmış/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Google Haritalar’da aç/ })).not.toBeInTheDocument()
    cleanup()

    // No match on Google is for the owner to check: users see the place as usual
    availability = { status: 'NOT_FOUND', mapsUrl: 'https://www.google.com/maps/search/Seyir/@40.647,35.82,17z', googleName: null }
    renderPage()
    expect(await screen.findByRole('link', { name: /Google Haritalar’da aç/ })).toBeInTheDocument()
    expect(screen.queryByText(/Kapanmış olabilir/)).not.toBeInTheDocument()
    cleanup()

    authUser = { id: 1, role: 'ADMIN' }
    renderPage()
    expect(await screen.findByText(/eşleşen bir konum bulunamadı. Kapanmış olabilir/)).toBeInTheDocument()
    expect(screen.getByText('Bu uyarıyı sadece sen görüyorsun.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Haritada yine de ara/ })).toBeInTheDocument()
    authUser = null
  })

  it('never links a website that is not http(s)', async () => {
    current = place(7, 'Köşe Kahve', { source: 'OVERTURE', website: 'javascript:alert(1)' })
    renderPage()
    await screen.findByRole('heading', { name: 'Köşe Kahve' })
    expect(screen.queryByRole('link', { name: /Web sitesi/ })).not.toBeInTheDocument()
  })
})

describe('PlaceDetailPage owner review', () => {
  it('a place the owner marked as suspect: no warning for users, who can still report it', async () => {
    availability = null
    current = place(11, 'Seyir Cafe', { city: 'Amasya', latitude: 40.647, longitude: 35.82, suspect: true })
    renderPage()
    expect(await screen.findByRole('button', { name: /Bu mekanı bildir/ })).toBeInTheDocument()
    expect(screen.queryByText('Bu yer kapanmış olabilir.')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Mekanı kaldır/ })).not.toBeInTheDocument()
  })

  it('the owner sees the warning and ends a wrong one with “Bu yer var”', async () => {
    authUser = { id: 1, role: 'ADMIN' }
    reviewPlace.mockResolvedValue(undefined)
    availability = null
    current = place(11, 'Seyir Cafe', { city: 'Amasya', latitude: 40.647, longitude: 35.82, suspect: true })
    renderPage()
    expect(await screen.findByText('Bu yer kapanmış olabilir.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Bu yer var' }))

    await waitFor(() => expect(screen.queryByText('Bu yer kapanmış olabilir.')).not.toBeInTheDocument())
    expect(reviewPlace).toHaveBeenCalledWith(5, 'CONFIRM')
    expect(screen.getByRole('link', { name: /Google Haritalar’da aç/ })).toBeInTheDocument()
    authUser = null
  })
})
