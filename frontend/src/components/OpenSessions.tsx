import { LogOut, Monitor, Smartphone } from 'lucide-react'
import { useState } from 'react'
import { api } from '../api'
import type { OpenSession } from '../api/types'
import { useAuth } from '../context/AuthContext'
import { errorMessage, formatWhen } from '../lib/format'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'
import { ErrorState, Skeleton, useToast } from './ui'

const MOBILE = /Android|iOS/

// Profile sheet: devices signed in to the account; any other one can be signed out at once
export function OpenSessions() {
  const t = useT()
  const toast = useToast()
  const { logout } = useAuth()
  const list = useAsync(() => api.sessions(), [])
  const [busy, setBusy] = useState<number | 'others' | null>(null)

  async function end(session: OpenSession) {
    setBusy(session.id)
    try {
      await api.endSession(session.id)
      toast(t('Cihazın oturumu kapatıldı', 'Signed out on that device'))
      list.reload()
    } catch (e) {
      toast(errorMessage(e, t('Oturum kapatılamadı', 'Could not sign out that device')))
    } finally {
      setBusy(null)
    }
  }

  async function endOthers() {
    if (!window.confirm(t('Bu cihaz dışındaki tüm oturumlar kapatılsın mı?', 'Sign out on every other device?'))) return
    setBusy('others')
    try {
      const { ended } = await api.endOtherSessions()
      toast(t(`${ended} cihazdan çıkış yapıldı`, `Signed out on ${ended} device(s)`))
      list.reload()
    } catch (e) {
      toast(errorMessage(e, t('Oturumlar kapatılamadı', 'Could not sign out the other devices')))
    } finally {
      setBusy(null)
    }
  }

  if (list.loading) return <Skeleton height={120} />
  if (list.error || !list.data) return <ErrorState message={list.error ?? ''} onRetry={list.reload} />
  const sessions = list.data
  const others = sessions.filter(s => !s.current).length

  return (
    <div className="stack">
      <p className="t-caption">
        {t('Hesabına giriş yapılmış cihazlar. Tanımadığın bir cihaz görürsen oturumunu kapat ve şifreni değiştir.',
          'Devices signed in to your account. If you see one you don’t know, sign it out and change your password.')}
      </p>
      {sessions.map(s => {
        const Icon = s.device && MOBILE.test(s.device) ? Smartphone : Monitor
        return (
          <div key={s.id} className="card row" style={{ gap: 12 }}>
            <span className="list-item-icon"><Icon size={18} /></span>
            <div className="grow stack-sm" style={{ minWidth: 0 }}>
              <span className="t-headline">
                {s.device ?? t('Bilinmeyen cihaz', 'Unknown device')}
                {s.current && <span className="badge badge-success" style={{ marginLeft: 8 }}>{t('Bu cihaz', 'This device')}</span>}
              </span>
              <span className="t-caption">
                {t(`Son kullanım: ${formatWhen(s.lastUsedAt)}`, `Last used: ${formatWhen(s.lastUsedAt)}`)}
                {s.ipAddress ? ` · ${s.ipAddress}` : ''}
              </span>
            </div>
            {s.current ? (
              <button type="button" className="btn btn-ghost btn-sm" onClick={logout}>{t('Çıkış yap', 'Sign out')}</button>
            ) : (
              <button type="button" className="btn btn-secondary btn-sm" disabled={busy !== null} onClick={() => void end(s)}>
                {t('Çıkış yap', 'Sign out')}
              </button>
            )}
          </div>
        )
      })}
      {others > 0 && (
        <button type="button" className="btn btn-danger btn-block" disabled={busy !== null} onClick={() => void endOthers()}>
          <LogOut size={16} /> {t('Diğer tüm cihazlardan çıkış yap', 'Sign out on all other devices')}
        </button>
      )}
    </div>
  )
}
