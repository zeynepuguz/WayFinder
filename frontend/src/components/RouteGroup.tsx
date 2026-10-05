import { Copy, LogOut, Share2, Users, X } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { api } from '../api'
import type { Route } from '../api/types'
import { errorMessage } from '../lib/format'
import { inviteLink, isGroupRoute } from '../lib/group'
import { useT } from '../lib/i18n'
import { Alert, ConfirmSheet, useToast } from './ui'

// Group plan box on the route screen: the owner invites friends, a member sees whose route it is and can leave
export function RouteGroup({ route, onChange }: { route: Route; onChange: (route: Route) => void }) {
  const t = useT()
  const toast = useToast()
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [leaveOpen, setLeaveOpen] = useState(false)
  const group = route.group
  if (!group) return null

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(errorMessage(e, t('İşlem başarısız', 'Something went wrong')))
    } finally {
      setBusy(false)
    }
  }

  const share = () => run(async () => {
    const { token } = await api.shareRoute(route.id)
    onChange({ ...route, group: { ...group, shareToken: token } })
  })
  const unshare = () => run(async () => {
    await api.unshareRoute(route.id)
    onChange({ ...route, group: { ...group, shareToken: null } })
  })
  const leave = () => run(async () => {
    await api.leaveRoute(route.id)
    toast(t('Gruptan ayrıldın', 'You left the group'))
    navigate('/routes', { replace: true })
  })

  async function send(link: string) {
    const text = t(`${route.title}: birlikte planlayalım`, `${route.title}: let’s plan it together`)
    try {
      if (navigator.share) {
        await navigator.share({ title: route.title, text, url: link })
        return
      }
      await navigator.clipboard.writeText(link)
      toast(t('Davet bağlantısı kopyalandı', 'Invite link copied'))
    } catch {
      // the user closed the share sheet
    }
  }

  const others = group.members.length - 1
  const link = group.shareToken ? inviteLink(group.shareToken) : null

  return (
    <section className="section">
      <h2 className="t-headline row" style={{ gap: 8 }}><Users size={20} /> {t('Grup planı', 'Group plan')}</h2>
      {group.owner && !isGroupRoute(route) && (
        <>
          <p className="t-caption">{t('Arkadaşlarını davet et: rotayı görsünler, durakları oylasınlar; Nomi Premium’u olanlar birlikte düzenleyebilir.',
            'Invite friends: they see the route and vote on its stops; those with Nomi Premium can change it with you.')}</p>
          <button className="btn btn-secondary btn-block" disabled={busy} onClick={() => void share()}><Share2 size={18} /> {t('Davet bağlantısı oluştur', 'Create an invite link')}</button>
        </>
      )}
      {group.members.length > 1 && (
        <div className="chips" aria-label={t('Gruptakiler', 'In the group')}>
          {group.members.map((name, i) => <span key={`${name}-${i}`} className={`badge ${i === 0 ? 'badge-brand' : ''}`}>{name}{i === 0 ? t(' (rotayı yapan)', ' (planner)') : ''}</span>)}
        </div>
      )}
      {group.owner && link && (
        <div className="stack-sm">
          <span className="input"><input readOnly value={link} aria-label={t('Davet bağlantısı', 'Invite link')} onFocus={e => e.currentTarget.select()} /></span>
          <div className="row" style={{ gap: 8 }}>
            <button className="btn btn-primary grow" onClick={() => void send(link)}>
              {'share' in navigator ? <Share2 size={18} /> : <Copy size={18} />} {t('Davet gönder', 'Send invite')}
            </button>
            <button className="btn btn-ghost" disabled={busy} onClick={() => void unshare()}><X size={18} /> {t('Bağlantıyı kapat', 'Turn off link')}</button>
          </div>
          <p className="t-caption">{others > 0
            ? t(`${others} kişi katıldı. Bağlantıyı kapatırsan yeni kimse katılamaz; katılanlar kalır.`, `${others} joined. Turning the link off stops new people joining; those in stay.`)
            : t('Bağlantıyı alan, giriş yaptıktan sonra gruba katılır. Uygulamada Rotalarım > Kodla katıl ile de girilebilir.', 'Whoever gets the link joins after signing in. In the app: My routes > Join with a code.')}</p>
        </div>
      )}
      {!group.owner && (
        <>
          <p className="t-caption">{t(`Bu rota ${group.members[0]} ile birlikte planlanıyor. Durakları oylayabilirsin.`, `You are planning this route with ${group.members[0]}. You can vote on its stops.`)}</p>
          <button className="btn btn-ghost btn-block" style={{ color: 'var(--danger)' }} onClick={() => setLeaveOpen(true)}><LogOut size={18} /> {t('Gruptan ayrıl', 'Leave the group')}</button>
        </>
      )}
      {error && <Alert tone="danger"><span>{error}</span></Alert>}
      <ConfirmSheet open={leaveOpen} onClose={() => setLeaveOpen(false)} label={t('Gruptan ayrılsın mı?', 'Leave the group?')}
                    text={t('Rota listenden çıkar ve oyların silinir. Tekrar katılmak için yeni bir davet gerekir.', 'The route leaves your list and your votes are removed. You need a new invite to join again.')}
                    confirmLabel={t('Ayrıl', 'Leave')} busy={busy} error={error} onConfirm={() => void leave()} />
    </section>
  )
}
