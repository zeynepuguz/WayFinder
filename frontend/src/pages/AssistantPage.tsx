import { ArrowUp, ChevronRight, Crown, MessagesSquare, Sparkles, SquarePen } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { api } from '../api'
import { ApiRequestError } from '../api/client'
import type { ConversationSummary, Recommendation, Route } from '../api/types'
import { ConversationList } from '../components/assistant/ConversationList'
import { RoutePreview } from '../components/assistant/RoutePreview'
import { Locked } from '../components/gate'
import { HScroll } from '../components/HScroll'
import { FartherPlaceRow, PlaceRow } from '../components/PlaceViews'
import { Alert, Sheet } from '../components/ui'
import { useAuth } from '../context/AuthContext'
import { useUserLocation } from '../context/LocationContext'
import { errorMessage } from '../lib/format'
import { tr, useT } from '../lib/i18n'

interface Message {
  key: string
  role: 'USER' | 'ASSISTANT'
  content: string
  routeId?: number | null
  route?: Route | null
  recommendations?: Recommendation[]
  // lower-ranked extras: a better fit, but not close by
  fartherRecommendations?: Recommendation[]
}

// Functions, not constants: the texts follow the language chosen at runtime
const starters = () => [
  tr('2 kişiyiz, 700 TL bütçemiz var, kahvaltı ve kahve istiyoruz', 'We’re 2 people with 700 TL, we’d like breakfast and coffee'),
  tr('Bu şehre ilk defa geliyorum, bir günlük rota planla', 'It’s my first time in this city, plan a day for me'),
  tr('Yakında iyi bir kahveci öner', 'Suggest a good coffee place nearby'),
]
const duringTrip = () => [
  tr('Çok yorulduk', 'We’re really tired'),
  tr('Yağmur başladı', 'It started raining'),
  tr('Biraz daha tarihi yer ekle', 'Add a few more historic sights'),
  tr('Sıradaki durak ne?', 'What’s the next stop?'),
  tr('Hava nasıl?', 'How’s the weather?'),
]

// A prompt from another screen (/assistant?new=1&q=…) is sent once, even when React StrictMode runs effects twice
// or the page remounts: the same text is not auto-sent again within a few seconds
let lastAutoSend: { text: string; at: number } | null = null

/** For tests: forget the last auto-sent prompt */
export function resetAutoSend() {
  lastAutoSend = null
}

export function AssistantPage() {
  const { user, hasAccess } = useAuth()
  const t = useT()

  if (!user) {
    return (
      <main className="screen">
        <Locked icon={Sparkles} title={t('Kişisel şehir asistanın', 'Your personal city assistant')}
                text={t('Bütçeni, kaç kişi olduğunuzu ve ne istediğini yaz; Nomi gerçek mekan, saat ve mesafe bilgileriyle gününü planlasın.',
                        'Tell Nomi your budget, how many of you there are and what you’d like to do; it plans your day with real places, opening hours and distances.')} />
      </main>
    )
  }
  return <Chat canSend={hasAccess} />
}

/**
 * One assistant chat. /assistant?c=<id> shows chat <id>; /assistant alone is a new, empty chat that is created by
 * its first message. Every chat has its own route, so "Çok yorulduk" etc. only change the route planned here.
 */
function Chat({ canSend }: { canSend: boolean }) {
  const t = useT()
  const location = useUserLocation()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const conversationParam = Number(params.get('c'))
  const conversationId = Number.isInteger(conversationParam) && conversationParam > 0 ? conversationParam : null

  const [messages, setMessages] = useState<Message[]>([])
  const [conversations, setConversations] = useState<ConversationSummary[]>([])
  const [listOpen, setListOpen] = useState(false)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [loadingChat, setLoadingChat] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)
  // The chat the page just created by sending: its messages are already on screen, no reload needed
  const createdHere = useRef<number | null>(null)

  const loadConversations = useCallback(() => {
    api.conversations().then(setConversations).catch(() => {})
  }, [])

  useEffect(loadConversations, [loadConversations])

  useEffect(() => {
    setError(null)
    if (conversationId == null) {
      setMessages([])
      return
    }
    if (createdHere.current === conversationId) {
      createdHere.current = null
      return
    }
    let cancelled = false
    setMessages([])
    setLoadingChat(true)
    api.conversationMessages(conversationId)
      .then(history => {
        if (!cancelled) setMessages(history.map(m => ({ key: `h${m.id}`, role: m.role, content: m.content, routeId: m.routeId })))
      })
      .catch(e => {
        if (!cancelled) setError(errorMessage(e, t('Sohbet yüklenemedi', 'Could not load the chat')))
      })
      .finally(() => {
        if (!cancelled) setLoadingChat(false)
      })
    return () => { cancelled = true }
  }, [conversationId])

  // A prompt chosen elsewhere (/assistant?new=1&q=…) always starts a new chat, sent once the location is known
  useEffect(() => {
    const q = params.get('q')?.trim()
    if (!q || !canSend || location.source === 'loading') return
    setParams({}, { replace: true })
    if (lastAutoSend && lastAutoSend.text === q && Date.now() - lastAutoSend.at < 5000) return
    lastAutoSend = { text: q, at: Date.now() }
    setMessages([])
    void send(q, null)
  }, [params, location.source, canSend])

  useEffect(() => {
    bottomRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'end' })
  }, [messages, sending])

  async function send(text: string, chatId: number | null = conversationId) {
    const message = text.trim()
    if (!message || sending) return

    setError(null)
    setInput('')
    setSending(true)
    setMessages(current => [...current, { key: `u${Date.now()}`, role: 'USER', content: message }])

    try {
      const reply = await api.sendMessage(message, location.latitude, location.longitude, chatId)
      setMessages(current => [...current, {
        key: `a${Date.now()}`, role: 'ASSISTANT', content: reply.reply,
        route: reply.route, routeId: reply.route?.id, recommendations: reply.recommendations,
        fartherRecommendations: reply.fartherRecommendations ?? [],
      }])
      if (reply.conversationId && reply.conversationId !== chatId) {
        createdHere.current = reply.conversationId
        setParams({ c: String(reply.conversationId) }, { replace: true })
      }
      loadConversations()
    } catch (e) {
      if (!(e instanceof ApiRequestError && e.status === 402)) {
        setError(errorMessage(e, t('Mesaj gönderilemedi', 'Message could not be sent')))
      }
    } finally {
      setSending(false)
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    void send(input)
  }

  function open(id: number | null) {
    setListOpen(false)
    navigate(id == null ? '/assistant' : `/assistant?c=${id}`)
  }

  const current = conversations.find(c => c.id === conversationId)
  // Quick replies change a route, so they only make sense in a chat that has one
  const hasRoute = current?.routeId != null || messages.some(m => m.routeId)

  return (
    <main className="screen chat-screen">
      <header className="chat-header">
        <span className="bot-avatar"><Sparkles size={20} /></span>
        <div className="grow" style={{ minWidth: 0 }}>
          <h1 className="t-headline">{t('Nomi Asistan', 'Nomi Assistant')}</h1>
          <p className="t-caption" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {current?.title ?? t('Gerçek mekan, saat ve hava verisiyle plan yapar', 'Plans with real places, opening hours and weather')}
          </p>
        </div>
        <button className="icon-btn" onClick={() => setListOpen(true)} aria-label={t('Sohbetler', 'Chats')} title={t('Sohbetler', 'Chats')}>
          <MessagesSquare size={19} />
        </button>
        <button className="icon-btn" onClick={() => open(null)} aria-label={t('Yeni sohbet', 'New chat')} title={t('Yeni sohbet', 'New chat')}>
          <SquarePen size={19} />
        </button>
      </header>

      <div className="chat" aria-live="polite">
        {messages.length === 0 && !sending && !loadingChat && conversationId == null && (
          <div className="card card-pad-lg stack">
            <h2 className="t-title">{t('Merhaba! Bugün nasıl bir gün istersin?', 'Hi! What kind of day would you like?')}</h2>
            <p className="ink-2">{t('Kaç kişi olduğunuzu, bütçeni ve ne yapmak istediğini yaz. Gezerken “çok yorulduk” ya da “yağmur başladı” dersen rotanı hemen güncellerim.',
              'Tell me how many of you there are, your budget and what you’d like to do. While you’re out, just say “we’re tired” or “it started raining” and I’ll update your route right away.')}</p>
            <div className="stack-sm">
              {starters().map(s => (
                <button key={s} className="list-item card" style={{ borderRadius: 14, minHeight: 48 }}
                        onClick={() => void send(s)} disabled={!canSend}>
                  <Sparkles size={16} color="var(--brand)" />
                  <span className="grow" style={{ fontSize: 14, fontWeight: 600 }}>{s}</span>
                  <ChevronRight size={16} className="muted" />
                </button>
              ))}
            </div>
          </div>
        )}

        {messages.map(m => m.role === 'USER' ? (
          <div key={m.key} className="msg msg-user"><div className="bubble bubble-user">{m.content}</div></div>
        ) : (
          <div key={m.key} className="stack-sm">
            <div className="msg msg-bot">
              <span className="bot-avatar"><Sparkles size={15} /></span>
              <div className="bubble bubble-bot">{m.route ? withoutStopLines(m.content) : m.content}</div>
            </div>
            {(m.route || m.routeId || (m.recommendations && m.recommendations.length > 0)
              || (m.fartherRecommendations && m.fartherRecommendations.length > 0)) && (
              <div className="chat-attachment">
                {m.route && <RoutePreview route={m.route} />}
                {!m.route && m.routeId && (
                  <Link to={`/routes/${m.routeId}`} className="section-link">{t('Rotayı aç', 'Open route')} <ChevronRight size={16} /></Link>
                )}
                {m.recommendations?.map(r => <PlaceRow key={r.place.id} place={r.place} />)}
                {m.fartherRecommendations && m.fartherRecommendations.length > 0 && (
                  <div className="stack-sm">
                    <h3 className="t-overline">{t('Daha uygun ama sana yakın değil', 'A better fit, but not close to you')}</h3>
                    <div className="farther-list">
                      {m.fartherRecommendations.map(r => <FartherPlaceRow key={r.place.id} recommendation={r} />)}
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        ))}

        {sending && (
          <div className="msg msg-bot">
            <span className="bot-avatar"><Sparkles size={15} /></span>
            <div className="bubble bubble-bot typing" aria-label={t('Nomi yazıyor', 'Nomi is typing')}><span /><span /><span /></div>
          </div>
        )}
        {error && <Alert tone="danger"><span>{error}</span></Alert>}
        <div ref={bottomRef} />
      </div>

      <div className="composer">
        {canSend ? (
          <>
            {hasRoute && (
              <HScroll className="suggestions">
                {duringTrip().map(q => (
                  <button key={q} className="chip" onClick={() => void send(q)} disabled={sending}>{q}</button>
                ))}
              </HScroll>
            )}
            <form className="composer-box" onSubmit={submit}>
              <input value={input} onChange={e => setInput(e.target.value)} placeholder={t('Nomi’ye yaz…', 'Message Nomi…')} maxLength={1000}
                     aria-label={t('Mesaj', 'Message')} enterKeyHint="send" />
              <button type="submit" className="send-btn" disabled={sending || !input.trim()} aria-label={t('Gönder', 'Send')}><ArrowUp size={20} /></button>
            </form>
          </>
        ) : (
          <button className="btn btn-premium btn-lg btn-block" onClick={() => navigate('/premium?next=/assistant')}>
            <Crown size={18} /> {t('Asistanı kullanmak için Premium’a geç', 'Go Premium to use the assistant')}
          </button>
        )}
      </div>

      <Sheet open={listOpen} onClose={() => setListOpen(false)} label={t('Sohbetler', 'Chats')}>
        <ConversationList
          conversations={conversations}
          currentId={conversationId}
          canEdit={canSend}
          onOpen={open}
          onChanged={updated => setConversations(list => list.map(c => (c.id === updated.id ? updated : c)))}
          onDeleted={id => {
            setConversations(list => list.filter(c => c.id !== id))
            if (id === conversationId) open(null)
          }}
        />
      </Sheet>
    </main>
  )
}

// The route card below already lists the stops, so the text keeps only the summary lines
function withoutStopLines(text: string): string {
  return text
    .split('\n')
    .filter(line => !/^\d{2}:\d{2}(:\d{2})? →/.test(line.trim()))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
}
