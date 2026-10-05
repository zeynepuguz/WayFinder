import { useEffect, useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import { api } from '../api'
import { BackButton, ErrorState, Spinner } from '../components/ui'
import { useAuth } from '../context/AuthContext'
import { errorMessage } from '../lib/format'
import { inviteCode } from '../lib/group'
import { useT } from '../lib/i18n'

// /join/<code>: a friend's invite link. Joins after signing in, then opens the route
export function JoinPage() {
  const { token = '' } = useParams()
  const { user } = useAuth()
  const navigate = useNavigate()
  const t = useT()
  const [error, setError] = useState<string | null>(null)
  const code = inviteCode(token)

  useEffect(() => {
    if (!user || !code) return
    let current = true
    api.joinRoute(code)
      .then(({ routeId }) => current && navigate(`/routes/${routeId}`, { replace: true }))
      .catch(e => current && setError(errorMessage(e, t('Gruba katılınamadı', 'Couldn’t join the group'))))
    return () => { current = false }
  }, [user, code, navigate, t])

  if (!user) return <Navigate to={`/login?next=${encodeURIComponent(`/join/${token}`)}`} replace />
  return (
    <main className="screen">
      <BackButton to="/routes" />
      {code && !error
        ? <div className="row" style={{ gap: 8, justifyContent: 'center' }}><Spinner /> {t('Gruba katılıyorsun…', 'Joining the group…')}</div>
        : <ErrorState message={error ?? t('Davet kodu geçersiz', 'The invite code is not valid')} />}
    </main>
  )
}
