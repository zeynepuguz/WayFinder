import { AlertTriangle, EyeOff, Flag, Inbox, MessageSquare, RotateCcw, Trash2, X } from 'lucide-react'
import { useState } from 'react'
import { Link, Navigate } from 'react-router'
import { api } from '../api'
import type { ReviewAction, ReviewedPlace } from '../api/types'
import { BackButton, EmptyState, ErrorState, Segmented, Skeleton, useToast } from '../components/ui'
import { useAuth } from '../context/AuthContext'
import { CATEGORY_LABELS, formatDateTime } from '../lib/format'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'

type Tab = 'reports' | 'suspects' | 'removed' | 'feedback'

// The owner's admin area: users' reports, suspect and removed places (by city), suggestions for the app
export function AdminPage() {
  const t = useT()
  const { user } = useAuth()
  const [tab, setTab] = useState<Tab>('reports')
  const counts = useAsync(() => api.reviewCounts(), [tab])

  if (user && user.role !== 'ADMIN') return <Navigate to="/" replace />
  if (!user) return <Navigate to="/login?next=%2Fadmin" replace />

  const c = counts.data
  const label = (text: string, n?: number) => (n ? `${text} (${n})` : text)

  return (
    <main className="screen screen-no-tabbar">
      <div className="row" style={{ gap: 12, marginBottom: 16 }}>
        <BackButton />
        <h1 className="t-title">{t('Yönetim', 'Admin')}</h1>
      </div>
      <Segmented<Tab> value={tab} onChange={setTab} options={[
        { value: 'reports', label: label(t('Bildirilenler', 'Reports'), c?.openReports) },
        { value: 'suspects', label: label(t('Şüpheliler', 'Suspects'), c?.suspects) },
        { value: 'removed', label: label(t('Silinenler', 'Removed'), c?.removed) },
        { value: 'feedback', label: label(t('Öneriler', 'Feedback'), c?.unreadFeedback) },
      ]} />
      <div style={{ marginTop: 16 }}>
        {tab === 'reports' && <Reports onChanged={counts.reload} />}
        {tab === 'suspects' && <ReviewedList kind="suspects" onChanged={counts.reload} />}
        {tab === 'removed' && <ReviewedList kind="removed" onChanged={counts.reload} />}
        {tab === 'feedback' && <FeedbackList onChanged={counts.reload} />}
      </div>
    </main>
  )
}

function usePlaceAction(onDone: () => void) {
  const t = useT()
  const toast = useToast()
  return async (id: number, action: ReviewAction | 'DISMISS') => {
    if (action === 'REMOVE' && !window.confirm(t('Bu mekan kullanıcılardan kaldırılsın mı?', 'Remove this place for users?'))) return
    try {
      if (action === 'DISMISS') await api.dismissReports(id)
      else await api.reviewPlace(id, action)
      toast(t('Tamam', 'Done'))
      onDone()
    } catch (e) {
      toast(e instanceof Error ? e.message : t('İşlem yapılamadı', 'Could not do that'))
    }
  }
}

function PlaceRow({ place, children }: { place: ReviewedPlace; children: React.ReactNode }) {
  const t = useT()
  const where = [place.district, place.city].filter(Boolean).join(', ')
  return (
    <div className="card stack-sm">
      <Link to={`/places/${place.id}`} className="t-headline">{place.name}</Link>
      <span className="t-caption">{CATEGORY_LABELS[place.category]}{where ? ` · ${where}` : ''}</span>
      {place.reports > 0 && (
        <span className="t-caption">
          {t(`${place.reports} bildirim`, `${place.reports} reports`)} · {place.lastReason === 'WRONG_LOCATION'
            ? t('Konumu yanlış', 'Wrong location') : t('Kapanmış', 'Closed')}
          {place.lastReportAt ? ` · ${formatDateTime(place.lastReportAt)}` : ''}
        </span>
      )}
      <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>{children}</div>
    </div>
  )
}

function Reports({ onChanged }: { onChanged: () => void }) {
  const t = useT()
  const list = useAsync(() => api.placeReports(), [])
  const act = usePlaceAction(() => { list.reload(); onChanged() })
  if (list.loading) return <Skeleton height={80} />
  if (list.error) return <ErrorState message={list.error} onRetry={list.reload} />
  if (!list.data?.length) return <EmptyState icon={Flag} title={t('Bildirim yok', 'No reports')} text={t('Kullanıcıların kapanmış dediği mekanlar burada görünür.', 'Places users say have closed show up here.')} />
  return (
    <div className="stack">
      {list.data.map(p => (
        <PlaceRow key={p.id} place={p}>
          <button type="button" className="btn btn-danger btn-sm" onClick={() => void act(p.id, 'REMOVE')}><EyeOff size={15} /> {t('Kaldır', 'Remove')}</button>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void act(p.id, 'SUSPECT')}><AlertTriangle size={15} /> {t('Şüpheli', 'Suspect')}</button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => void act(p.id, 'DISMISS')}><X size={15} /> {t('Bildirimi kapat', 'Dismiss')}</button>
        </PlaceRow>
      ))}
    </div>
  )
}

function ReviewedList({ kind, onChanged }: { kind: 'suspects' | 'removed'; onChanged: () => void }) {
  const t = useT()
  const cities = useAsync(() => api.cities(), [])
  const [city, setCity] = useState('')
  const list = useAsync(() => (kind === 'removed' ? api.removedPlaces(city || undefined) : api.suspectPlaces(city || undefined)), [kind, city])
  const act = usePlaceAction(() => { list.reload(); onChanged() })
  return (
    <div className="stack">
      <span className="input">
        <select value={city} onChange={e => setCity(e.target.value)} aria-label={t('Şehir', 'City')} style={{ width: '100%' }}>
          <option value="">{t('Tüm şehirler', 'All cities')}</option>
          {cities.data?.map(c => <option key={c.slug} value={c.slug}>{c.name}</option>)}
        </select>
      </span>
      {list.loading ? <Skeleton height={80} />
        : list.error ? <ErrorState message={list.error} onRetry={list.reload} />
          : !list.data?.length ? <EmptyState icon={kind === 'removed' ? Trash2 : AlertTriangle}
                                             title={kind === 'removed' ? t('Silinen mekan yok', 'No removed places') : t('Şüpheli mekan yok', 'No suspect places')}
                                             text={t('Mekan sayfasındaki Yönetim bölümünden işaretlediklerin burada görünür.', 'Places you mark on a place page show up here.')} />
            : list.data.map(p => (
              <PlaceRow key={p.id} place={p}>
                <span className="t-caption">{p.reviewedAt ? formatDateTime(p.reviewedAt) : ''}</span>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => void act(p.id, 'CLEAR')}><RotateCcw size={15} /> {t('Geri al', 'Restore')}</button>
                {kind === 'suspects' && <button type="button" className="btn btn-danger btn-sm" onClick={() => void act(p.id, 'REMOVE')}><EyeOff size={15} /> {t('Kaldır', 'Remove')}</button>}
              </PlaceRow>
            ))}
    </div>
  )
}

function FeedbackList({ onChanged }: { onChanged: () => void }) {
  const t = useT()
  const list = useAsync(() => api.feedbackList(), [])
  const markRead = async (id: number) => {
    await api.markFeedbackRead(id)
    list.reload()
    onChanged()
  }
  if (list.loading) return <Skeleton height={80} />
  if (list.error) return <ErrorState message={list.error} onRetry={list.reload} />
  if (!list.data?.length) return <EmptyState icon={Inbox} title={t('Öneri yok', 'No feedback')} text={t('Kullanıcıların uygulama önerileri burada görünür.', 'Users’ suggestions for the app show up here.')} />
  return (
    <div className="stack">
      {list.data.map(f => (
        <div key={f.id} className="card stack-sm" style={{ opacity: f.read ? 0.65 : 1 }}>
          <p style={{ whiteSpace: 'pre-wrap' }}>{f.message}</p>
          <span className="t-caption">{f.userEmail ?? t('Silinmiş hesap', 'Deleted account')} · {formatDateTime(f.createdAt)}</span>
          {!f.read && (
            <button type="button" className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => void markRead(f.id)}>
              <MessageSquare size={15} /> {t('Okundu', 'Mark as read')}
            </button>
          )}
        </div>
      ))}
    </div>
  )
}
