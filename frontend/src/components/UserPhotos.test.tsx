import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { ApiRequestError } from '../api/client'
import type { MyPhoto, UserPhoto } from '../api/types'
import { googleMapsSearchUrl } from '../lib/format'
import { AddPhotoSheet, MyPhotos, UserPhotosSection } from './UserPhotos'

const photos = vi.fn()
const uploadPhoto = vi.fn()
const myPhotos = vi.fn()
const deletePhoto = vi.fn()
vi.mock('../api', () => ({
  api: {
    photos: (...args: unknown[]) => photos(...args),
    uploadPhoto: (...args: unknown[]) => uploadPhoto(...args),
    myPhotos: (...args: unknown[]) => myPhotos(...args),
    deletePhoto: (...args: unknown[]) => deletePhoto(...args),
  },
}))
let auth: { user: object | null; hasAccess: boolean } = { user: null, hasAccess: false }
vi.mock('../context/AuthContext', () => ({ useAuth: () => auth }))

const photo = (id: number, uploader = 'Ayşe Yılmaz'): UserPhoto => ({
  id, url: `/media/photos/${id}.jpg`, thumbUrl: `/media/photos/${id}_t.jpg`, width: 800, height: 600,
  createdAt: '2026-09-01T10:00:00Z', uploader,
})

function Where() {
  const location = useLocation()
  return <p>at {location.pathname}{location.search}</p>
}

const MAPS = googleMapsSearchUrl('Moda Kahve', 'Kadıköy', 'İstanbul')

function renderSection() {
  render(
    <MemoryRouter initialEntries={['/places/5']}>
      <Routes>
        <Route path="/places/:id" element={
          <UserPhotosSection target={{ type: 'PLACE', id: 5 }} name="Moda Kahve" title="Kullanıcılarımızdan fotoğraflar" mapsUrl={MAPS} />
        } />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )
}

function stubGeolocation(result: 'granted' | 'denied') {
  const getCurrentPosition = vi.fn((ok: PositionCallback, fail: PositionErrorCallback, _options?: PositionOptions) => {
    if (result === 'granted') ok({ coords: { latitude: 40.987, longitude: 29.025, accuracy: 12 } } as GeolocationPosition)
    else fail({ code: 1, message: 'denied' } as GeolocationPositionError)
  })
  Object.defineProperty(navigator, 'geolocation', { value: { getCurrentPosition }, configurable: true })
  return getCurrentPosition
}

function jpeg(size = 1024, name = 'foto.jpg', type = 'image/jpeg') {
  const file = new File(['x'], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

beforeEach(() => {
  photos.mockReset().mockResolvedValue([])
  uploadPhoto.mockReset().mockResolvedValue({ id: 99, status: 'PENDING' })
  myPhotos.mockReset()
  deletePhoto.mockReset().mockResolvedValue(undefined)
  auth = { user: null, hasAccess: false }
})

describe('UserPhotosSection', () => {
  it('shows the collage and opens / closes the lightbox', async () => {
    photos.mockResolvedValue([photo(1), photo(2, 'Mehmet'), photo(3)])
    renderSection()

    const thumbs = await screen.findAllByRole('img', { name: 'Moda Kahve — kullanıcı fotoğrafı' })
    expect(thumbs).toHaveLength(3)
    expect(thumbs[0]).toHaveAttribute('src', '/media/photos/1_t.jpg')
    expect(thumbs[0]).toHaveAttribute('loading', 'lazy')
    expect(photos).toHaveBeenCalledWith({ type: 'PLACE', id: 5 })

    await userEvent.click(thumbs[1])
    const box = screen.getByRole('dialog', { name: 'Fotoğraf' })
    expect(within(box).getByRole('img')).toHaveAttribute('src', '/media/photos/2.jpg')
    expect(within(box).getByText(/^Mehmet · /)).toBeInTheDocument()
    expect(within(box).getByRole('button', { name: 'Kapat' })).toHaveFocus()

    await userEvent.keyboard('{ArrowRight}')
    expect(within(box).getByRole('img')).toHaveAttribute('src', '/media/photos/3.jpg')
    expect(within(box).getByText(/^Ayşe · /)).toBeInTheDocument()
    await userEvent.click(within(box).getByRole('button', { name: 'Sonraki fotoğraf' }))
    expect(within(box).getByRole('img')).toHaveAttribute('src', '/media/photos/1.jpg')

    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await userEvent.click(thumbs[0])
    await userEvent.click(screen.getByRole('button', { name: 'Kapat' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows the empty state and the Google Maps link', async () => {
    renderSection()
    expect(await screen.findByText('Henüz fotoğraf yok. İlk fotoğrafı sen ekle!')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /Daha fazla fotoğraf için Google Haritalar’da gör/ })
    expect(link).toHaveAttribute('href', MAPS)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(new URL(MAPS).searchParams.get('query')).toBe('Moda Kahve, Kadıköy İstanbul')
  })

  it('sends guests to login before adding a photo', async () => {
    renderSection()
    await userEvent.click(await screen.findByRole('button', { name: /Fotoğraf ekle/ }))
    expect(screen.getByText('at /login?next=%2Fplaces%2F5')).toBeInTheDocument()
  })

  it('opens the rules sheet for signed-in users (no pass needed)', async () => {
    auth = { user: { id: 1 }, hasAccess: false }
    renderSection()
    await userEvent.click(await screen.findByRole('button', { name: /Fotoğraf ekle/ }))
    const sheet = screen.getByRole('dialog', { name: 'Fotoğraf ekle' })
    expect(within(sheet).getByText(/Son 10 fotoğraf gösterilir/)).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: /Fotoğraf çek/ })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: /Galeriden seç/ })).toBeInTheDocument()
    expect(screen.getByTestId('photo-camera-input')).toHaveAttribute('capture', 'environment')
    expect(screen.getByTestId('photo-gallery-input')).not.toHaveAttribute('capture')
  })
})

describe('AddPhotoSheet', () => {
  function renderSheet() {
    render(<MemoryRouter><AddPhotoSheet open onClose={() => {}} target={{ type: 'PLACE', id: 5 }} /></MemoryRouter>)
  }

  it('refuses files over 12 MB without uploading', async () => {
    renderSheet()
    await userEvent.upload(screen.getByTestId('photo-gallery-input'), jpeg(13 * 1024 * 1024))
    expect(screen.getByText('Fotoğraf çok büyük. En fazla 12 MB olabilir.')).toBeInTheDocument()
    expect(uploadPhoto).not.toHaveBeenCalled()
  })

  it('asks for a JPEG instead of HEIC', async () => {
    renderSheet()
    // Some pickers ignore "accept": the app checks the file itself
    await userEvent.setup({ applyAccept: false }).upload(screen.getByTestId('photo-gallery-input'), jpeg(1024, 'IMG_1.HEIC', ''))
    expect(screen.getByText(/HEIC biçimindeki fotoğraflar desteklenmiyor/)).toBeInTheDocument()
    expect(uploadPhoto).not.toHaveBeenCalled()
  })

  it('uploads the original file with the device position and says it is in review', async () => {
    const getCurrentPosition = stubGeolocation('granted')
    renderSheet()
    const file = jpeg()
    await userEvent.upload(screen.getByTestId('photo-camera-input'), file)

    expect(await screen.findByText('Fotoğrafın inceleniyor; uygun bulunursa birkaç dakika içinde görünecek.')).toBeInTheDocument()
    expect(getCurrentPosition.mock.calls[0][2]).toMatchObject({ enableHighAccuracy: true, timeout: 10_000 })
    expect(uploadPhoto).toHaveBeenCalledWith({ type: 'PLACE', id: 5 }, file, { latitude: 40.987, longitude: 29.025, accuracy: 12 })
  })

  it('still uploads when location is denied', async () => {
    stubGeolocation('denied')
    renderSheet()
    await userEvent.upload(screen.getByTestId('photo-gallery-input'), jpeg())
    await screen.findByText(/Fotoğrafın inceleniyor/)
    expect(uploadPhoto.mock.calls[0][2]).toBeNull()
  })

  it('shows the reason right away when the location check rejects the photo', async () => {
    stubGeolocation('granted')
    uploadPhoto.mockResolvedValue({ id: 7, status: 'REJECTED', rejectReason: 'NOT_NEAR',
      rejectMessage: 'Fotoğraf bu mekanın yakınında çekilmemiş görünüyor.' })
    renderSheet()
    await userEvent.upload(screen.getByTestId('photo-gallery-input'), jpeg())
    expect(await screen.findByText('Fotoğraf bu mekanın yakınında çekilmemiş görünüyor.')).toBeInTheDocument()
    expect(screen.queryByText(/Fotoğrafın inceleniyor/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Başka bir fotoğraf dene' }))
    expect(screen.getByRole('button', { name: /Galeriden seç/ })).toBeInTheDocument()
  })

  it('shows the backend message for the daily limit', async () => {
    stubGeolocation('denied')
    uploadPhoto.mockRejectedValue(new ApiRequestError({ status: 429, message: 'Bugün en fazla 5 fotoğraf ekleyebilirsin.', errors: {} }))
    renderSheet()
    await userEvent.upload(screen.getByTestId('photo-gallery-input'), jpeg())
    expect(await screen.findByText('Bugün en fazla 5 fotoğraf ekleyebilirsin.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Galeriden seç/ })).toBeInTheDocument()
  })
})

describe('MyPhotos', () => {
  const mine = (id: number, status: MyPhoto['status'], extra: Partial<MyPhoto> = {}): MyPhoto => ({
    id, url: `/media/photos/${id}.jpg`, thumbUrl: `/media/photos/${id}_t.jpg`, status, rejectReason: null, rejectMessage: null,
    target: { type: 'PLACE', id: 10 + id, name: `Mekan ${id}` }, createdAt: '2026-09-01T10:00:00Z', ...extra,
  })

  function renderMine() {
    render(<MemoryRouter><MyPhotos /></MemoryRouter>)
  }

  it('lists my photos with their status and the reject reason', async () => {
    myPhotos.mockResolvedValue([
      mine(1, 'PENDING'),
      mine(2, 'APPROVED'),
      mine(3, 'REJECTED', { rejectReason: 'NOT_RELEVANT', rejectMessage: 'Fotoğraf mekanı göstermiyor.' }),
      mine(4, 'APPROVED', { target: { type: 'DISTRICT', name: 'Kadıköy', city: 'istanbul', district: 'kadikoy' } }),
    ])
    renderMine()
    expect(await screen.findByText('İnceleniyor')).toBeInTheDocument()
    expect(screen.getAllByText('Yayında')).toHaveLength(2)
    expect(screen.getByText('Reddedildi')).toBeInTheDocument()
    expect(screen.getByText('Fotoğraf mekanı göstermiyor.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Mekan 1' })).toHaveAttribute('href', '/places/11')
    expect(screen.getByText('Kadıköy')).toBeInTheDocument()
  })

  it('deletes a photo after confirmation', async () => {
    myPhotos.mockResolvedValue([mine(1, 'APPROVED'), mine(2, 'REJECTED')])
    renderMine()
    await screen.findByText('Mekan 1')
    await userEvent.click(screen.getAllByRole('button', { name: 'Fotoğrafı sil' })[0])
    const sheet = screen.getByRole('dialog', { name: 'Fotoğraf silinsin mi?' })

    await userEvent.click(within(sheet).getByRole('button', { name: 'Vazgeç' }))
    expect(deletePhoto).not.toHaveBeenCalled()
    expect(screen.getByText('Mekan 1')).toBeInTheDocument()

    await userEvent.click(screen.getAllByRole('button', { name: 'Fotoğrafı sil' })[0])
    await userEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Sil' }))
    expect(deletePhoto).toHaveBeenCalledWith(1)
    await waitFor(() => expect(screen.queryByText('Mekan 1')).not.toBeInTheDocument())
    expect(screen.getByText('Mekan 2')).toBeInTheDocument()
  })

  it('says so when there are no photos yet', async () => {
    myPhotos.mockResolvedValue([])
    renderMine()
    expect(await screen.findByText(/Henüz fotoğraf eklemedin/)).toBeInTheDocument()
  })
})
