import { Images, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import { mediaUrl } from '../api/client'
import { useAuth } from '../context/AuthContext'
import { errorMessage } from '../lib/format'
import { locale, useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'
import { Alert, EmptyState, ErrorState, Sheet, Skeleton } from './ui'

// A round profile photo, or the first letter of the username on a tinted circle
export function Avatar({ url, name, size = 32 }: { url?: string | null; name: string; size?: number }) {
  const [failed, setFailed] = useState(false)
  const src = !failed ? mediaUrl(url ?? null) : null
  const letter = name.replace(/^@/, '').charAt(0).toLocaleUpperCase(locale()) || '?'
  return (
    <span className="avatar" style={{ width: size, height: size, fontSize: Math.round(size * 0.42) }} aria-hidden>
      {src ? <img src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} /> : letter}
    </span>
  )
}

// "Ekim 2026" / "October 2026"
const monthYear = (yearMonth: string) =>
  new Date(`${yearMonth}-01T00:00:00`).toLocaleDateString(locale(), { month: 'long', year: 'numeric' })

/** What anyone sees of a user: profile photo, username, since when, the photos they added. Never the real name. */
export function UserProfileSheet({ username, onClose }: { username: string | null; onClose: () => void }) {
  return (
    <Sheet open={username !== null} onClose={onClose} label={username ? `@${username}` : ''}>
      {username && <ProfileBody username={username} />}
    </Sheet>
  )
}

function ProfileBody({ username }: { username: string }) {
  const t = useT()
  const { user } = useAuth()
  const { data, error, loading, reload } = useAsync(() => api.publicProfile(username), [username])
  const [removeError, setRemoveError] = useState<string | null>(null)

  if (loading && !data) return <Skeleton height={180} radius={14} />
  if (error || !data) return <ErrorState message={error ?? t('Profil yüklenemedi', 'Couldn’t load the profile')} onRetry={reload} />

  // Unsuitable profile photos: an admin removes them here
  async function removeAvatar() {
    setRemoveError(null)
    try {
      await api.removeAvatarOf(username)
      reload()
    } catch (e) {
      setRemoveError(errorMessage(e, t('Kaldırılamadı', 'Couldn’t remove it')))
    }
  }

  return (
    <div className="stack">
      <div className="row" style={{ gap: 14 }}>
        <Avatar url={data.avatarUrl} name={data.username} size={72} />
        <div className="grow stack-sm" style={{ gap: 2 }}>
          <strong className="t-title" style={{ fontSize: 20 }}>@{data.username}</strong>
          <span className="t-caption">{t(`${monthYear(data.memberSince)}’dan beri Nomi’de`, `On Nomi since ${monthYear(data.memberSince)}`)}</span>
          <span className="t-caption">{t(`${data.photoCount} fotoğraf ekledi`, `Added ${data.photoCount} ${data.photoCount === 1 ? 'photo' : 'photos'}`)}</span>
        </div>
      </div>

      {user?.role === 'ADMIN' && data.avatarUrl && (
        <button type="button" className="btn btn-sm btn-danger" style={{ alignSelf: 'flex-start' }} onClick={() => void removeAvatar()}>
          <Trash2 size={16} /> {t('Profil fotoğrafını kaldır', 'Remove profile photo')}
        </button>
      )}
      {removeError && <Alert tone="danger"><span>{removeError}</span></Alert>}

      {data.photos.length === 0 ? (
        <EmptyState icon={Images} title={t('Henüz fotoğraf yok', 'No photos yet')} />
      ) : (
        <div className="profile-photos">
          {data.photos.map(photo => {
            const thumb = <img src={mediaUrl(photo.thumbUrl) ?? undefined} alt={photo.placeName ?? ''} loading="lazy" decoding="async" />
            return photo.placeId ? (
              <Link key={photo.id} to={`/places/${photo.placeId}`} className="profile-photo" title={photo.placeName}>
                {thumb}
                {photo.placeName && <span className="profile-photo-name">{photo.placeName}</span>}
              </Link>
            ) : (
              <span key={photo.id} className="profile-photo">
                {thumb}
                {photo.placeName && <span className="profile-photo-name">{photo.placeName}</span>}
              </span>
            )
          })}
        </div>
      )}
    </div>
  )
}
