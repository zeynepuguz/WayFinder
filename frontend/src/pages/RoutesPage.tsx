import { Map as MapIcon, Plus, Users } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { api } from '../api'
import { Locked, useGate } from '../components/gate'
import { NewRouteForm } from '../components/NewRouteForm'
import { RouteCard } from '../components/RouteCard'
import { Alert, EmptyState, ErrorState, ListSkeleton, Segmented, Sheet } from '../components/ui'
import { useAuth } from '../context/AuthContext'
import { errorMessage, isPastRoute } from '../lib/format'
import { inviteCode } from '../lib/group'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'

export function RoutesPage() {
  const { user } = useAuth()
  const t = useT()
  if (!user) {
    return (
      <main className="screen">
        <Locked icon={MapIcon} title={t('Rotaların burada', 'Your routes live here')}
                text={t('Oluşturduğun ve gezdiğin rotalar burada saklanır. Başlamak için giriş yap.', 'Routes you create and walk are kept here. Sign in to get started.')} />
      </main>
    )
  }
  return <RouteList />
}

function RouteList() {
  const gate = useGate()
  const t = useT()
  const [tab, setTab] = useState<'all' | 'saved'>('all')
  const [creating, setCreating] = useState(false)
  const [joining, setJoining] = useState(false)
  const { data, error, loading, reload } = useAsync(() => api.routes(tab === 'saved'), [tab])

  const openCreate = () => gate('/routes') && setCreating(true)
  const current = data?.filter(route => !isPastRoute(route)) ?? []
  const past = data?.filter(route => isPastRoute(route)) ?? []

  return (
    <main className="screen">
      <div className="row-between">
        <h1 className="t-display">{t('Rotalarım', 'My routes')}</h1>
        <button type="button" className="btn btn-primary btn-sm" onClick={openCreate}><Plus size={18} /> {t('Yeni rota', 'New route')}</button>
      </div>

      <button type="button" className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => setJoining(true)}>
        <Users size={16} /> {t('Davet koduyla katıl', 'Join with an invite code')}
      </button>

      <Segmented value={tab} onChange={setTab} options={[{ value: 'all', label: t('Tümü', 'All') }, { value: 'saved', label: t('Kaydedilenler', 'Saved') }]} />

      {loading && <ListSkeleton rows={3} height={92} />}
      {error && <ErrorState message={error} onRetry={reload} />}
      {data?.length === 0 && (
        <EmptyState
          icon={MapIcon}
          title={tab === 'saved' ? t('Kaydedilen rota yok', 'No saved routes') : t('Henüz rotan yok', 'No routes yet')}
          text={tab === 'saved' ? t('Bir rotanın detayında kalbe dokunarak kaydedebilirsin.', 'Tap the heart on a route to save it.') : t('Birkaç tercihle ilk rotanı oluştur ya da asistana ne istediğini yaz.', 'Create your first route with a few choices, or tell the assistant what you’d like.')}
          action={tab === 'all' && <button type="button" className="btn btn-primary" onClick={openCreate}><Plus size={18} /> {t('Rota oluştur', 'Create route')}</button>}
        />
      )}
      {current.length > 0 && (
        <div className="stack">
          {current.map(route => <RouteCard key={route.id} route={route} />)}
        </div>
      )}
      {/* Routes of days that are over: read-only, never "active" */}
      {past.length > 0 && (
        <section className="section" aria-label={t('Geçmiş rotalar', 'Past routes')}>
          <h2 className="t-headline">{t('Geçmiş rotalar', 'Past routes')}</h2>
          <div className="stack">
            {past.map(route => <RouteCard key={route.id} route={route} />)}
          </div>
        </section>
      )}

      <Sheet open={creating} onClose={() => setCreating(false)} label={t('Yeni rota', 'New route')}>
        <NewRouteForm />
      </Sheet>

      <Sheet open={joining} onClose={() => setJoining(false)} label={t('Gruba katıl', 'Join a group')}>
        <JoinForm />
      </Sheet>
    </main>
  )
}

// A friend's invite link or code, pasted (the app cannot open links from other apps by itself)
function JoinForm() {
  const t = useT()
  const navigate = useNavigate()
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const code = inviteCode(input)

  async function join() {
    if (!code) return
    setBusy(true)
    setError(null)
    try {
      const { routeId } = await api.joinRoute(code)
      navigate(`/routes/${routeId}`)
    } catch (e) {
      setError(errorMessage(e, t('Gruba katılınamadı', 'Couldn’t join the group')))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="stack" onSubmit={e => { e.preventDefault(); void join() }}>
      <p className="t-caption">{t('Arkadaşının gönderdiği davet bağlantısını ya da kodu yapıştır.', 'Paste the invite link or code your friend sent.')}</p>
      <span className="input">
        <input value={input} onChange={e => setInput(e.target.value)} maxLength={300} autoFocus
               aria-label={t('Davet bağlantısı veya kodu', 'Invite link or code')} placeholder="https://…/join/…" />
      </span>
      {error && <Alert tone="danger"><span>{error}</span></Alert>}
      <button type="submit" className="btn btn-primary btn-block" disabled={busy || !code}>{t('Katıl', 'Join')}</button>
    </form>
  )
}
