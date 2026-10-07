import { AlertTriangle, Bot, EyeOff, Flag, Inbox, MessageSquare, RotateCcw, Trash2, X } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { Link, Navigate } from 'react-router'
import { api } from '../api'
import type { ReviewAction, ReviewedPlace } from '../api/types'
import { Alert, BackButton, EmptyState, ErrorState, Segmented, Skeleton, useToast } from '../components/ui'
import { useAuth } from '../context/AuthContext'
import { CATEGORY_LABELS, errorMessage, formatDate, formatDateTime } from '../lib/format'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'

type Tab = 'reports' | 'suspects' | 'removed' | 'feedback'

// The owner's admin area: users' reports, suspect and removed places (by city), suggestions for the app
export function AdminPage() {
  const { user } = useAuth()
  if (!user) return <Navigate to="/login?next=%2Fadmin" replace />
  if (user.role !== 'ADMIN') return <Navigate to="/" replace />
  // Only an admin mounts the area, so its admin-only requests are never sent for anyone else
  return <AdminArea />
}

function AdminArea() {
  const t = useT()
  const [tab, setTab] = useState<Tab>('reports')
  const counts = useAsync(() => api.reviewCounts(), [tab])
  const c = counts.data
  const label = (text: string, n?: number) => (n ? `${text} (${n})` : text)

  return (
    <main className="screen screen-no-tabbar">
      <div className="row" style={{ gap: 12, marginBottom: 16 }}>
        <BackButton />
        <h1 className="t-title">{t('Yönetim', 'Admin')}</h1>
      </div>
      <AiUsageCard />
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

// Estimated USD; a single call costs fractions of a cent
function usd(value: number): string {
  return `$${value > 0 && value < 0.01 ? value.toFixed(4) : value.toFixed(2)}`
}

// OpenAI spending: this month against the budget lock, today, last days and the heaviest users (details folded)
function AiUsageCard() {
  const t = useT()
  const usage = useAsync(() => api.aiUsage(), [])
  if (usage.loading) return <div style={{ marginBottom: 16 }}><Skeleton height={72} /></div>
  if (usage.error || !usage.data) return null
  const u = usage.data
  const share = u.monthlyBudgetUsd > 0 ? Math.min(1, u.month.costUsd / u.monthlyBudgetUsd) : 0
  const barColor = u.budgetReached ? 'var(--danger)' : share >= 0.8 ? 'var(--warning)' : 'var(--success)'
  return (
    <details className="card stack-sm" style={{ marginBottom: 16 }}>
      <summary className="row" style={{ gap: 10, cursor: 'pointer', listStyle: 'none' }}>
        <Bot size={18} />
        <span className="grow t-headline">{t('Yapay zekâ bu ay', 'AI this month')}</span>
        <span className="t-headline">{usd(u.month.costUsd)}{u.monthlyBudgetUsd > 0 ? ` / ${usd(u.monthlyBudgetUsd)}` : ''}</span>
      </summary>
      {u.monthlyBudgetUsd > 0 && (
        <div style={{ height: 6, borderRadius: 3, background: 'var(--line)', overflow: 'hidden' }}
             role="meter" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(share * 100)}
             aria-label={t('Aylık bütçe', 'Monthly budget')}>
          <div style={{ width: `${share * 100}%`, height: '100%', background: barColor }} />
        </div>
      )}
      {u.budgetReached && (
        <Alert tone="warning">{t('Aylık bütçe doldu: asistan ay sonuna kadar yapay zekâsız cevap veriyor, fotoğraflar bekliyor.',
          'Monthly budget used up: the assistant answers without AI and photos wait until next month.')}</Alert>
      )}
      <span className="t-caption">
        {t(`Bugün ${u.today.calls} istek · ${usd(u.today.costUsd)}`, `Today ${u.today.calls} calls · ${usd(u.today.costUsd)}`)}
        {' · '}
        {t(`Bu ay ${u.month.calls} istek, ${u.month.failures} hata`, `This month ${u.month.calls} calls, ${u.month.failures} failed`)}
        {' · '}
        {(u.month.inputTokens + u.month.outputTokens).toLocaleString()} token
      </span>
      {u.days.length > 0 && (
        <div className="stack-sm">
          <span className="field-label">{t('Son günler', 'Last days')}</span>
          {u.days.slice(-7).reverse().map(d => (
            <span key={d.day} className="t-caption">{formatDate(d.day)} · {t(`${d.calls} istek`, `${d.calls} calls`)} · {usd(d.costUsd)}</span>
          ))}
        </div>
      )}
      {u.topUsersMonth.length > 0 && (
        <div className="stack-sm">
          <span className="field-label">{t('En çok kullananlar (bu ay)', 'Top users (this month)')}</span>
          {u.topUsersMonth.map(x => (
            <span key={x.userId} className="t-caption">{x.email ?? t('Silinmiş hesap', 'Deleted account')} · {t(`${x.calls} istek`, `${x.calls} calls`)} · {usd(x.costUsd)}</span>
          ))}
        </div>
      )}
      <span className="t-caption">{t('Tahmini tutar (token × liste fiyatı). Kesin fatura OpenAI panelindedir.', 'Estimate (tokens × list price). The exact bill is in the OpenAI dashboard.')}</span>
    </details>
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
      toast(errorMessage(e, t('İşlem yapılamadı', 'Could not do that')))
    }
  }
}

// Every list here: loading, failed (with retry), empty, or its items
function renderList<T>(list: { loading: boolean; error: string | null; data: T[] | null; reload: () => void },
                       empty: ReactNode, render: (items: T[]) => ReactNode): ReactNode {
  if (list.loading) return <Skeleton height={80} />
  if (list.error) return <ErrorState message={list.error} onRetry={list.reload} />
  if (!list.data?.length) return empty
  return render(list.data)
}

function ReviewRow({ place, children }: { place: ReviewedPlace; children: ReactNode }) {
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
  const empty = <EmptyState icon={Flag} title={t('Bildirim yok', 'No reports')} text={t('Kullanıcıların kapanmış dediği mekanlar burada görünür.', 'Places users say have closed show up here.')} />
  return renderList(list, empty, places => (
    <div className="stack">
      {places.map(p => (
        <ReviewRow key={p.id} place={p}>
          <button type="button" className="btn btn-danger btn-sm" onClick={() => void act(p.id, 'REMOVE')}><EyeOff size={15} /> {t('Kaldır', 'Remove')}</button>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void act(p.id, 'SUSPECT')}><AlertTriangle size={15} /> {t('Şüpheli', 'Suspect')}</button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => void act(p.id, 'DISMISS')}><X size={15} /> {t('Bildirimi kapat', 'Dismiss')}</button>
        </ReviewRow>
      ))}
    </div>
  ))
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
      {renderList(list, <EmptyState icon={kind === 'removed' ? Trash2 : AlertTriangle}
                                    title={kind === 'removed' ? t('Silinen mekan yok', 'No removed places') : t('Şüpheli mekan yok', 'No suspect places')}
                                    text={t('Mekan sayfasındaki Yönetim bölümünden işaretlediklerin burada görünür.', 'Places you mark on a place page show up here.')} />,
        places => places.map(p => (
          <ReviewRow key={p.id} place={p}>
            <span className="t-caption">{p.reviewedAt ? formatDateTime(p.reviewedAt) : ''}</span>
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => void act(p.id, 'CLEAR')}><RotateCcw size={15} /> {t('Geri al', 'Restore')}</button>
            {kind === 'suspects' && <button type="button" className="btn btn-danger btn-sm" onClick={() => void act(p.id, 'REMOVE')}><EyeOff size={15} /> {t('Kaldır', 'Remove')}</button>}
          </ReviewRow>
        )))}
    </div>
  )
}

function FeedbackList({ onChanged }: { onChanged: () => void }) {
  const t = useT()
  const list = useAsync(() => api.feedbackList(), [])
  const toast = useToast()
  const markRead = async (id: number) => {
    try {
      await api.markFeedbackRead(id)
      list.reload()
      onChanged()
    } catch (e) {
      toast(errorMessage(e, t('İşlem yapılamadı', 'Could not do that')))
    }
  }
  const empty = <EmptyState icon={Inbox} title={t('Öneri yok', 'No feedback')} text={t('Kullanıcıların uygulama önerileri burada görünür.', 'Users’ suggestions for the app show up here.')} />
  return renderList(list, empty, items => (
    <div className="stack">
      {items.map(f => (
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
  ))
}
