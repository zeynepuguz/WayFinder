import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StrictMode } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import type { AssistantReply, ChatMessage, ConversationSummary, Recommendation, Route as RouteT } from '../api/types'
import { place } from '../test/fixtures'
import { AssistantPage, resetAutoSend } from './AssistantPage'

const sendMessage = vi.fn()
const conversations = vi.fn()
const conversationMessages = vi.fn()
const renameConversation = vi.fn()
const deleteConversation = vi.fn()
vi.mock('../api', () => ({
  api: {
    sendMessage: (...args: unknown[]) => sendMessage(...args),
    conversations: () => conversations(),
    conversationMessages: (id: number) => conversationMessages(id),
    renameConversation: (...args: unknown[]) => renameConversation(...args),
    deleteConversation: (id: number) => deleteConversation(id),
  },
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
  recommendations: [rec(1, 'Yakın Kafe', ['Şu an açık'], null)], changes: [], conversationId: 7, ...extra,
})

const chat = (id: number, title: string, routeId: number | null): ConversationSummary =>
  ({ id, title, routeId, routeTitle: routeId ? '28 Eylül Pazartesi Rotası' : null, lastMessageAt: '2026-09-28T10:00:00Z', preview: null })

const message = (id: number, role: 'USER' | 'ASSISTANT', content: string, routeId: number | null = null): ChatMessage =>
  ({ id, role, content, routeId, createdAt: '2026-09-28T10:00:00Z' })

const route = { id: 5, title: 'Bugünkü rota', stops: [], totalEstimatedCost: 0 } as unknown as RouteT

function CurrentUrl() {
  const location = useLocation()
  return <output data-testid="url">{location.pathname + location.search}</output>
}

function renderAt(url: string, strict = false) {
  const tree = (
    <MemoryRouter initialEntries={[url]}>
      <Routes><Route path="/assistant" element={<><AssistantPage /><CurrentUrl /></>} /></Routes>
    </MemoryRouter>
  )
  return render(strict ? <StrictMode>{tree}</StrictMode> : tree)
}

async function ask() {
  renderAt('/assistant')
  await userEvent.type(screen.getByRole('textbox', { name: 'Mesaj' }), 'Kahve öner')
  await userEvent.click(screen.getByRole('button', { name: 'Gönder' }))
  await screen.findByText('İşte önerilerim')
}

beforeAll(() => { Element.prototype.scrollIntoView = vi.fn() })
beforeEach(() => {
  vi.clearAllMocks()
  resetAutoSend()
  conversations.mockResolvedValue([])
  conversationMessages.mockResolvedValue([])
})

describe('AssistantPage suggestions', () => {
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

describe('AssistantPage conversations', () => {
  it('starts a new chat with a prompt from the home screen and sends it only once (StrictMode)', async () => {
    sendMessage.mockResolvedValue(reply({ conversationId: 42 }))
    renderAt(`/assistant?new=1&q=${encodeURIComponent('Kahve öner')}`, true)

    await screen.findByText('İşte önerilerim')
    expect(sendMessage).toHaveBeenCalledTimes(1)
    // no chat id: the backend creates a new chat instead of continuing an old one
    expect(sendMessage).toHaveBeenCalledWith('Kahve öner', 40.991, 29.023, null)
    await waitFor(() => expect(screen.getByTestId('url')).toHaveTextContent('/assistant?c=42'))
    // the chat created here is not reloaded (its messages are already on screen)
    expect(conversationMessages).not.toHaveBeenCalled()
    expect(sendMessage).toHaveBeenCalledTimes(1)
  })

  it('a plain /assistant is an empty new chat with the starter prompts, not an older chat', async () => {
    conversations.mockResolvedValue([chat(1, 'Dünkü sohbet', 5)])
    renderAt('/assistant')

    expect(await screen.findByText('Merhaba! Bugün nasıl bir gün istersin?')).toBeInTheDocument()
    expect(conversationMessages).not.toHaveBeenCalled()
  })

  it('loads the messages of the chosen chat and switches between chats', async () => {
    conversations.mockResolvedValue([chat(1, 'Kadıköy günü', 5), chat(2, 'Kahve sorusu', null)])
    conversationMessages.mockImplementation((id: number) => Promise.resolve(id === 1
      ? [message(11, 'USER', 'Kadıköy’de gün planla'), message(12, 'ASSISTANT', 'Rotan hazır', 5)]
      : [message(21, 'USER', 'Kahve öner'), message(22, 'ASSISTANT', 'Moda’da iki kafe var')]))
    renderAt('/assistant?c=1')

    expect(await screen.findByText('Rotan hazır')).toBeInTheDocument()
    expect(conversationMessages).toHaveBeenCalledWith(1)

    await userEvent.click(screen.getByRole('button', { name: 'Sohbetler' }))
    const list = screen.getByRole('dialog', { name: 'Sohbetler' })
    await userEvent.click(within(list).getByRole('button', { name: /^Kahve sorusu/ }))

    expect(await screen.findByText('Moda’da iki kafe var')).toBeInTheDocument()
    expect(screen.queryByText('Rotan hazır')).not.toBeInTheDocument()
    expect(screen.getByTestId('url')).toHaveTextContent('/assistant?c=2')

    // Messages go to the open chat
    sendMessage.mockResolvedValue(reply({ conversationId: 2 }))
    await userEvent.type(screen.getByRole('textbox', { name: 'Mesaj' }), 'Başka öner')
    await userEvent.click(screen.getByRole('button', { name: 'Gönder' }))
    await waitFor(() => expect(sendMessage).toHaveBeenCalledWith('Başka öner', 40.991, 29.023, 2))
  })

  it('shows the route quick replies only in a chat that has a route', async () => {
    conversations.mockResolvedValue([chat(1, 'Kadıköy günü', 5), chat(2, 'Kahve sorusu', null)])
    conversationMessages.mockResolvedValue([message(21, 'USER', 'Kahve öner'), message(22, 'ASSISTANT', 'Moda’da iki kafe var')])
    const { unmount } = renderAt('/assistant?c=2')
    await screen.findByText('Moda’da iki kafe var')
    expect(screen.queryByRole('button', { name: 'Çok yorulduk' })).not.toBeInTheDocument()
    unmount()

    renderAt('/assistant?c=1')
    expect(await screen.findByRole('button', { name: 'Çok yorulduk' })).toBeInTheDocument()
  })

  it('shows the quick replies once this chat has planned a route', async () => {
    renderAt('/assistant')
    await screen.findByText('Merhaba! Bugün nasıl bir gün istersin?')
    expect(screen.queryByRole('button', { name: 'Yağmur başladı' })).not.toBeInTheDocument()

    sendMessage.mockResolvedValue(reply({ reply: 'Rotan hazır', route, recommendations: [], conversationId: 3 }))
    await userEvent.type(screen.getByRole('textbox', { name: 'Mesaj' }), 'Gün planla')
    await userEvent.click(screen.getByRole('button', { name: 'Gönder' }))

    expect(await screen.findByRole('button', { name: 'Yağmur başladı' })).toBeInTheDocument()
  })

  it('renames and deletes a chat after confirming', async () => {
    conversations.mockResolvedValue([chat(1, 'Kadıköy günü', 5)])
    conversationMessages.mockResolvedValue([message(11, 'USER', 'Kadıköy’de gün planla')])
    renameConversation.mockResolvedValue(chat(1, 'Cumartesi planı', 5))
    deleteConversation.mockResolvedValue(undefined)
    renderAt('/assistant?c=1')
    await screen.findByText('Kadıköy’de gün planla')

    await userEvent.click(screen.getByRole('button', { name: 'Sohbetler' }))
    await userEvent.click(await screen.findByRole('button', { name: '“Kadıköy günü” adını değiştir' }))
    const nameInput = screen.getByRole('textbox', { name: 'Sohbet adı' })
    await userEvent.clear(nameInput)
    await userEvent.type(nameInput, 'Cumartesi planı')
    await userEvent.click(screen.getByRole('button', { name: 'Kaydet' }))
    expect(renameConversation).toHaveBeenCalledWith(1, 'Cumartesi planı')
    expect(await screen.findByRole('button', { name: /^Cumartesi planı/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '“Cumartesi planı” sohbetini sil' }))
    expect(deleteConversation).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Sil' }))

    await waitFor(() => expect(deleteConversation).toHaveBeenCalledWith(1))
    // the open chat was deleted: back to a new, empty chat
    await waitFor(() => expect(screen.getByTestId('url')).toHaveTextContent(/^\/assistant$/))
  })
})
