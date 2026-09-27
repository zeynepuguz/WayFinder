import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { PHOTO, place } from '../test/fixtures'
import { PhotoCredit, PlaceCard, PlaceRow } from './PlaceViews'

vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: null, hasAccess: false }) }))
vi.mock('../context/SavedPlacesContext', () => ({ useSavedPlaces: () => ({ isSaved: () => false, toggle: vi.fn() }) }))

describe('place photos', () => {
  it('shows the photo lazily with the place name as alt text', () => {
    render(<MemoryRouter><PlaceRow place={place(1, 'Haydarpaşa Garı', { image: PHOTO })} /></MemoryRouter>)
    const img = screen.getByRole('img', { name: 'Haydarpaşa Garı' })
    expect(img).toHaveAttribute('src', PHOTO.url)
    expect(img).toHaveAttribute('loading', 'lazy')
    expect(img).toHaveAttribute('decoding', 'async')
  })

  it('falls back to the category tile when the photo fails or is missing', () => {
    const { container } = render(<MemoryRouter><PlaceCard place={place(1, 'Moda Parkı', { image: PHOTO })} /></MemoryRouter>)
    fireEvent.error(screen.getByRole('img', { name: 'Moda Parkı' }))
    expect(screen.queryByRole('img', { name: 'Moda Parkı' })).not.toBeInTheDocument()
    expect(container.querySelector('.tile-CAFE svg')).not.toBeNull()

    render(<MemoryRouter><PlaceRow place={place(2, 'Köşe Kahve')} /></MemoryRouter>)
    expect(screen.queryByRole('img', { name: 'Köşe Kahve' })).not.toBeInTheDocument()
  })

  it('never renders a non-http photo url', () => {
    render(<MemoryRouter><PlaceRow place={place(1, 'X', { image: { ...PHOTO, url: 'javascript:alert(1)' } })} /></MemoryRouter>)
    expect(screen.queryByRole('img', { name: 'X' })).not.toBeInTheDocument()
  })
})

describe('PhotoCredit', () => {
  it('credits author, license and Commons with a safe link', () => {
    render(<PhotoCredit image={PHOTO} />)
    const link = screen.getByRole('link', { name: /Fotoğraf: Jane Doe · CC BY-SA 4.0 · Wikimedia Commons/ })
    expect(link).toHaveAttribute('href', PHOTO.sourceUrl)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('omits missing parts', () => {
    render(<PhotoCredit image={{ ...PHOTO, author: null, license: ' ', sourceUrl: null }} />)
    expect(screen.getByText('Fotoğraf: Wikimedia Commons')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
