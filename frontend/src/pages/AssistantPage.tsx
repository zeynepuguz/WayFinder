import { ArrowUp, ChevronRight, Crown, Sparkles } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { api } from '../api'
import { ApiRequestError } from '../api/client'
import type { Recommendation, Route } from '../api/types'
import { Locked } from '../components/gate'
import { PlaceRow } from '../components/PlaceViews'
import { Alert } from '../components/ui'
import { STOP_ICON } from '../components/visuals'
import { useAuth } from '../context/AuthContext'
import { useUserLocation } from '../context/LocationContext'
import { formatCost, formatTime } from '../lib/format'

interface Message {
  key: string
  role: 'USER' | 'ASSISTANT'
  content: string
  routeId?: number | null
  route?: Route | null
  recommendations?: Recommendation[]
}

const STARTERS = [
  '2 kişiyiz, 700 TL bütçemiz var, kahvaltı ve kahve istiyoruz',
  'Kadıköy’e ilk defa geliyorum, bir günlük rota planla',
  'Yakında iyi bir kahveci öner',
]
const DURING_TRIP = ['Çok yorulduk', 'Yağmur başladı', 'Biraz daha tarihi yer ekle', 'Sıradaki durak ne?', 'Hava nasıl?']

export function AssistantPage() {
  const { user, hasAccess } = useAuth()

  if (!user) {
    return (
      <main className="screen">
        <Locked icon={Sparkles} title="Kişisel şehir asistanın"
                text="Bütçeni, kaç kişi olduğunuzu ve ne istediğini yaz; Nomi gerçek mekan, saat ve mesafe bilgileriyle gününü planlasın." />
      </main>
    )
  }
  return <Chat canSend={hasAccess} />
}

function Chat({ canSend }: { canSend: boolean }) {
  const location = useUserLocation()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)
  const autoSent = useRef(false)

  useEffect(() => {
    api.messages()
      .then(history => setMessages(history.map(m => ({ key: `h${m.id}`, role: m.role, content: m.content, routeId: m.routeId }))))
      .catch(() => setMessages([]))
  }, [])

  // A prompt chosen elsewhere (/assistant?q=...) is sent once the location is known
  useEffect(() => {
    const q = params.get('q')
    if (q && canSend && !autoSent.current && location.source !== 'loading') {
      autoSent.current = true
      setParams({}, { replace: true })
      void send(q)
    }
  }, [params, location.source, canSend])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })
  }, [messages, sending])

  async function send(text: string) {
    const message = text.trim()
    if (!message || sending) return

    setError(null)
    setInput('')
    setSending(true)
    setMessages(current => [...current, { key: `u${Date.now()}`, role: 'USER', content: message }])

    try {
      const reply = await api.sendMessage(message, location.latitude, location.longitude)
      setMessages(current => [...current, {
        key: `a${Date.now()}`, role: 'ASSISTANT', content: reply.reply,
        route: reply.route, routeId: reply.route?.id, recommendations: reply.recommendations,
      }])
    } catch (e) {
      if (!(e instanceof ApiRequestError && e.status === 402)) {
        setError(e instanceof Error ? e.message : 'Mesaj gönderilemedi')
      }
    } finally {
      setSending(false)
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    void send(input)
  }

  const hasRouteInChat = messages.some(m => m.routeId)

  return (
    <main className="screen chat-screen">
      <header className="chat-header">
        <span className="bot-avatar"><Sparkles size={20} /></span>
        <div className="grow">
          <h1 className="t-headline">Nomi Asistan</h1>
          <p className="t-caption">Gerçek mekan, saat ve hava verisiyle plan yapar</p>
        </div>
      </header>

      <div className="chat" aria-live="polite">
        {messages.length === 0 && !sending && (
          <div className="card card-pad-lg stack">
            <h2 className="t-title">Merhaba! Bugün nasıl bir gün istersin?</h2>
            <p className="ink-2">Kaç kişi olduğunuzu, bütçeni ve ne yapmak istediğini yaz. Gezerken “çok yorulduk” ya da “yağmur başladı” dersen rotanı hemen güncellerim.</p>
            <div className="stack-sm">
              {STARTERS.map(s => (
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
            {(m.route || m.routeId || (m.recommendations && m.recommendations.length > 0)) && (
              <div className="chat-attachment">
                {m.route && <RoutePreview route={m.route} />}
                {!m.route && m.routeId && (
                  <Link to={`/routes/${m.routeId}`} className="section-link">Rotayı aç <ChevronRight size={16} /></Link>
                )}
                {m.recommendations?.map(r => <PlaceRow key={r.place.id} place={r.place} />)}
              </div>
            )}
          </div>
        ))}

        {sending && (
          <div className="msg msg-bot">
            <span className="bot-avatar"><Sparkles size={15} /></span>
            <div className="bubble bubble-bot typing" aria-label="Nomi yazıyor"><span /><span /><span /></div>
          </div>
        )}
        {error && <Alert tone="danger"><span>{error}</span></Alert>}
        <div ref={bottomRef} />
      </div>

      <div className="composer">
        {canSend ? (
          <>
            {(hasRouteInChat || messages.length > 0) && (
              <div className="suggestions">
                {DURING_TRIP.map(q => (
                  <button key={q} className="chip" onClick={() => void send(q)} disabled={sending}>{q}</button>
                ))}
              </div>
            )}
            <form className="composer-box" onSubmit={submit}>
              <input value={input} onChange={e => setInput(e.target.value)} placeholder="Nomi’ye yaz…" maxLength={1000}
                     aria-label="Mesaj" enterKeyHint="send" />
              <button className="send-btn" disabled={sending || !input.trim()} aria-label="Gönder"><ArrowUp size={20} /></button>
            </form>
          </>
        ) : (
          <button className="btn btn-premium btn-lg btn-block" onClick={() => navigate('/premium?next=/assistant')}>
            <Crown size={18} /> Asistanı kullanmak için Premium’a geç
          </button>
        )}
      </div>
    </main>
  )
}

function RoutePreview({ route }: { route: Route }) {
  const upcoming = route.stops.filter(s => s.status === 'PLANNED')
  return (
    <Link to={`/routes/${route.id}`} className="card route-mini card-press">
      <div className="row-between">
        <span className="t-headline" style={{ fontSize: 15 }}>{route.title}</span>
        <span className="badge badge-brand">~{route.totalEstimatedCost.toLocaleString('tr-TR')} TL</span>
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
      <span className="section-link">Haritada gör <ChevronRight size={16} /></span>
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
