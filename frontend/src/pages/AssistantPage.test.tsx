import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import type { AssistantReply, Recommendation } from '../api/types'
import { place } from '../test/fixtures'
import { AssistantPage } from './AssistantPage'

const sendMessage = vi.fn()
vi.mock('../api', () => ({
  api: { messages: () => Promise.resolve([]), sendMessage: (...args: unknown[]) => sendMessage(...args) },
}))
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ user: { id: 1 }, hasAccess: true }) }))
vi.mock('../context/SavedPlacesContext', () => ({ useSavedPlaces: () => ({ isSaved: () => false, toggle: vi.fn() }) }))
vi.mock('../context/LocationContext', () => ({
  useUserLocation: () => ({ latitude: 40.991, longitude: 29.023, source: 'gps', refresh: () => {} }),
}))

const rec = (id: number, name: string, reasons: string[], whyBetter: string | null): Recommendation =>
  ({ place: place(id, name), type: 'COFFEE', score: 1, reasons, whyBetter })

const reply = (extra: Partial<AssistantReply>): AssistantReply => ({
  reply: 'İşte önerilerim', intent: { type: 'RECOMMEND', source: null }, route: null,
  recommendations: [rec(1, 'Yakın Kafe', ['Şu an açık'], null)], changes: [], ...extra,
})

async function ask() {
  render(<MemoryRouter><AssistantPage /></MemoryRouter>)
  await userEvent.type(screen.getByRole('textbox', { name: 'Mesaj' }), 'Kahve öner')
  await userEvent.click(screen.getByRole('button', { name: 'Gönder' }))
  await screen.findByText('İşte önerilerim')
}

describe('AssistantPage suggestions', () => {
  beforeAll(() => { Element.prototype.scrollIntoView = vi.fn() })

  it('lists farther but better places below the close-by picks', async () => {
    sendMessage.mockResolvedValue(reply({
      fartherRecommendations: [rec(9, 'Uzak Müze', ['4,4 km uzakta (yürüyerek ~77 dk)'], 'Yağmurda kapalı alan')],
    }))
    await ask()

    const heading = screen.getByText('Daha uygun ama sana yakın değil')
    const near = screen.getByText('Yakın Kafe')
    expect(near.compareDocumentPosition(heading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByText('4,4 km uzakta (yürüyerek ~77 dk)')).toBeInTheDocument()
    expect(screen.getByText('Yağmurda kapalı alan')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Uzak Müze/ })).toHaveAttribute('href', '/places/9')
  })

  it('shows no farther section when the reply has none', async () => {
    sendMessage.mockResolvedValue(reply({}))
    await ask()
    expect(screen.getByText('Yakın Kafe')).toBeInTheDocument()
    expect(screen.queryByText('Daha uygun ama sana yakın değil')).not.toBeInTheDocument()
  })
})
