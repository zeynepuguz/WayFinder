import { ArrowUp, Check, ChevronRight, Crown, MessagesSquare, Pencil, Sparkles, SquarePen, Trash2, X } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { api } from '../api'
import { ApiRequestError } from '../api/client'
import type { ConversationSummary, Recommendation, Route } from '../api/types'
import { Locked } from '../components/gate'
import { HScroll } from '../components/HScroll'
import { FartherPlaceRow, PlaceRow } from '../components/PlaceViews'
import { Alert, Sheet } from '../components/ui'
import { STOP_ICON } from '../components/visuals'
import { useAuth } from '../context/AuthContext'
import { useUserLocation } from '../context/LocationContext'
import { formatCost, formatTime, routeCost } from '../lib/format'
import { locale, tr, useT } from '../lib/i18n'

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
        if (!cancelled) setError(e instanceof Error ? e.message : t('Sohbet yüklenemedi', 'Could not load the chat'))
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
        setError(e instanceof Error ? e.message : t('Mesaj gönderilemedi', 'Message could not be sent'))
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

function ConversationList({ conversations, currentId, canEdit, onOpen, onChanged, onDeleted }: {
  conversations: ConversationSummary[]
  currentId: number | null
  canEdit: boolean
  onOpen: (id: number | null) => void
  onChanged: (conversation: ConversationSummary) => void
  onDeleted: (id: number) => void
}) {
  const t = useT()
  const [editing, setEditing] = useState<number | null>(null)
  const [title, setTitle] = useState('')
  const [confirming, setConfirming] = useState<ConversationSummary | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(e instanceof Error ? e.message : t('İşlem başarısız', 'Something went wrong'))
    } finally {
      setBusy(false)
    }
  }

  const rename = (event: FormEvent, id: number) => {
    event.preventDefault()
    if (!title.trim()) return
    void run(async () => {
      onChanged(await api.renameConversation(id, title.trim()))
      setEditing(null)
    })
  }

  if (confirming) {
    return (
      <div className="stack">
        <p className="ink-2">
          {t(`“${confirming.title ?? 'Yeni sohbet'}” sohbeti ve mesajları kalıcı olarak silinsin mi? Oluşturduğu rota Rotalarım’da kalır.`,
            `Permanently delete the chat “${confirming.title ?? 'New chat'}” and its messages? Its route stays in My routes.`)}
        </p>
        {error && <Alert tone="danger"><span>{error}</span></Alert>}
        <div className="row">
          <button className="btn btn-secondary grow" onClick={() => setConfirming(null)} disabled={busy}>{t('Vazgeç', 'Cancel')}</button>
          <button className="btn btn-danger grow" disabled={busy} onClick={() => void run(async () => {
            await api.deleteConversation(confirming.id)
            onDeleted(confirming.id)
            setConfirming(null)
          })}>{t('Sil', 'Delete')}</button>
        </div>
      </div>
    )
  }

  return (
    <div className="stack">
      <button className="btn btn-primary btn-block" onClick={() => onOpen(null)}>
        <SquarePen size={18} /> {t('Yeni sohbet', 'New chat')}
      </button>
      {error && <Alert tone="danger"><span>{error}</span></Alert>}
      {conversations.length === 0 ? (
        <p className="t-caption" style={{ textAlign: 'center' }}>{t('Henüz bir sohbetin yok.', 'You have no chats yet.')}</p>
      ) : (
        <div className="list-group">
          {conversations.map(c => editing === c.id ? (
            <form key={c.id} className="list-item" onSubmit={e => rename(e, c.id)}>
              <span className="input grow" style={{ minHeight: 44 }}>
                <input value={title} onChange={e => setTitle(e.target.value)} maxLength={120} autoFocus
                       aria-label={t('Sohbet adı', 'Chat name')} />
              </span>
              <button type="submit" className="icon-btn icon-btn-plain" disabled={busy || !title.trim()} aria-label={t('Kaydet', 'Save')}><Check size={18} /></button>
              <button type="button" className="icon-btn icon-btn-plain" onClick={() => setEditing(null)} aria-label={t('Vazgeç', 'Cancel')}><X size={18} /></button>
            </form>
          ) : (
            <div key={c.id} className="list-item" style={c.id === currentId ? { background: 'var(--brand-50)' } : undefined}>
              <button className="grow" style={{ minWidth: 0, background: 'none', border: 'none', padding: 0, textAlign: 'left', color: 'inherit' }}
                      onClick={() => onOpen(c.id)} aria-current={c.id === currentId ? 'page' : undefined}>
                <strong style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {c.title ?? t('Yeni sohbet', 'New chat')}
                </strong>
                <span className="t-caption" style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {c.routeTitle ? `${c.routeTitle} · ` : ''}{c.preview ?? ''}
                </span>
                <span className="t-caption muted">{formatWhen(c.lastMessageAt)}</span>
              </button>
              {canEdit && (
                <>
                  <button className="icon-btn icon-btn-plain" aria-label={t(`“${c.title ?? 'Yeni sohbet'}” adını değiştir`, `Rename “${c.title ?? 'New chat'}”`)}
                          onClick={() => { setEditing(c.id); setTitle(c.title ?? '') }}>
                    <Pencil size={17} />
                  </button>
                  <button className="icon-btn icon-btn-plain" style={{ color: 'var(--danger)' }}
                          aria-label={t(`“${c.title ?? 'Yeni sohbet'}” sohbetini sil`, `Delete “${c.title ?? 'New chat'}”`)}
                          onClick={() => setConfirming(c)}>
                    <Trash2 size={17} />
                  </button>
                </>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

// "Bugün 14:05" / "27 Eyl 18:30"
function formatWhen(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const time = date.toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit' })
  return date.toDateString() === new Date().toDateString()
    ? `${tr('Bugün', 'Today')} ${time}`
    : `${date.toLocaleDateString(locale(), { day: 'numeric', month: 'short' })} ${time}`
}

function RoutePreview({ route }: { route: Route }) {
  const t = useT()
  const upcoming = route.stops.filter(s => s.status === 'PLANNED')
  return (
    <Link to={`/routes/${route.id}`} className="card route-mini card-press">
      <div className="row-between">
        <span className="t-headline" style={{ fontSize: 15 }}>{route.title}</span>
        <span className="badge badge-brand">
          {routeCost(route.totalEstimatedCost, route.stops.some(s => s.place.estimatedCost != null))}
        </span>
      </div>
      <ol>
        {upcoming.slice(0, 7).map(stop => {
          const Icon = STOP_ICON[stop.type]
          return (
            <li key={stop.id}>
              <span className="time">{formatTime(stop.plannedStart)}</span>
              <span className="stop-icon"><Icon size={15} /></span>
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {stop.place.name} <span className="muted" style={{ fontWeight: 500 }}>· {formatCost(stop.place.estimatedCost)}</span>
              </span>
            </li>
          )
        })}
      </ol>
      <span className="section-link">{t('Haritada gör', 'View on map')} <ChevronRight size={16} /></span>
    </Link>
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
