import { Crown, LogIn, type LucideIcon } from 'lucide-react'
import { useCallback } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { useAuth } from '../context/AuthContext'
import { useT } from '../lib/i18n'

/**
 * Guests can look around; using Nomi needs an account and an active pass.
 * gate() returns true when the action may run, otherwise it sends the user
 * to login or to the paywall and comes back here afterwards.
 */
export function useGate() {
  const { user, hasAccess } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  return useCallback((next?: string) => {
    const target = encodeURIComponent(next ?? location.pathname + location.search)
    if (!user) {
      navigate(`/login?next=${target}`)
      return false
    }
    if (!hasAccess) {
      navigate(`/premium?next=${target}`)
      return false
    }
    return true
  }, [user, hasAccess, navigate, location.pathname, location.search])
}

// Full-screen teaser for a paid screen, shown instead of the content
export function Locked({ icon: Icon, title, text }: { icon: LucideIcon; title: string; text: string }) {
  const { user } = useAuth()
  const gate = useGate()
  const t = useT()

  return (
    <div className="locked">
      <div className="locked-art"><Icon size={40} strokeWidth={1.8} /></div>
      <h2 className="t-title">{title}</h2>
      <p className="ink-2" style={{ maxWidth: 320 }}>{text}</p>
      <button className={`btn btn-lg btn-block ${user ? 'btn-premium' : 'btn-primary'}`} onClick={() => gate()}
              style={{ maxWidth: 360, marginTop: 8 }}>
        {user
          ? <><Crown size={18} /> {t('Nomi Premium’a geç', 'Get Nomi Premium')}</>
          : <><LogIn size={18} /> {t('Giriş yap veya kaydol', 'Log in or sign up')}</>}
      </button>
      {!user && <p className="t-caption">{t('Hesabın varsa giriş yaptıktan sonra devam edebilirsin.', 'Already have an account? Log in to continue.')}</p>}
    </div>
  )
}
