import { Clock, Heart, Info, MapPin, Navigation, Timer, Umbrella, Wallet } from 'lucide-react'
import { useParams } from 'react-router'
import { api } from '../api'
import { useGate } from '../components/gate'
import { OpenBadge } from '../components/PlaceViews'
import { RouteMap } from '../components/RouteMap'
import { BackButton, ErrorState, Skeleton } from '../components/ui'
import { CATEGORY_ICON, Rating } from '../components/visuals'
import { useSavedPlaces } from '../context/SavedPlacesContext'
import { CATEGORY_LABELS, DAY_NAMES, formatCost, formatTime, TAG_LABELS } from '../lib/format'
import { useAsync } from '../lib/useAsync'

export function PlaceDetailPage() {
  const { id } = useParams()
  const saved = useSavedPlaces()
  const gate = useGate()
  const { data: place, error, loading, reload } = useAsync(() => api.place(Number(id)), [id])

  if (loading) {
    return (
      <main className="screen">
        <Skeleton height={220} radius={0} />
        <Skeleton height={28} width="70%" />
        <Skeleton height={120} radius={20} />
      </main>
    )
  }
  if (error || !place) {
    return <main className="screen"><BackButton /><ErrorState message={error ?? 'Mekan bulunamadı'} onRetry={reload} /></main>
  }

  const Icon = CATEGORY_ICON[place.category]
  const isSaved = saved.isSaved(place.id)
  const today = (new Date().getDay() + 6) % 7 + 1
  const todayHours = place.openingHours.filter(h => h.dayOfWeek === today)
  const mapsUrl = `https://www.google.com/maps/dir/?api=1&destination=${place.latitude},${place.longitude}&travelmode=walking`

  return (
    <main className="screen screen-no-tabbar" style={{ paddingBottom: 'calc(var(--safe-bottom) + 110px)' }}>
      <div className="place-hero">
        <div className={`tile tile-${place.category}`}>
          <span className="hero-icon"><Icon size={44} strokeWidth={1.6} /></span>
        </div>
        <div className="place-hero-bar">
          <BackButton glass />
          <button className="icon-btn icon-btn-glass" aria-pressed={isSaved} aria-label={isSaved ? 'Kaydedilenlerden çıkar' : 'Kaydet'}
                  onClick={() => gate() && void saved.toggle(place)}>
            <Heart size={20} fill={isSaved ? '#ff5a36' : 'none'} color={isSaved ? '#ff5a36' : 'currentColor'} />
          </button>
        </div>
      </div>

      <div className="place-sheet place-sheet-bleed">
        <div className="stack-sm">
          <span className="t-overline">{CATEGORY_LABELS[place.category]}{place.neighborhood && ` · ${place.neighborhood}`}</span>
          <h1 className="t-title" style={{ fontSize: 26, lineHeight: '32px' }}>{place.name}</h1>
          <div className="row wrap" style={{ gap: 8 }}>
            <Rating value={place.rating} />
            <OpenBadge openNow={place.openNow} />
            {place.indoor ? <span className="badge badge-sea"><Umbrella size={12} /> Kapalı alan</span>
              : <span className="badge">Açık alan</span>}
          </div>
        </div>

        {place.description && <p className="ink-2">{place.description}</p>}

        <div className="card" style={{ padding: '4px 16px' }}>
          {place.address && <div className="info-row"><MapPin size={18} />{place.address}</div>}
          <div className="info-row">
            <Clock size={18} />
            {place.openingHours.length === 0 ? 'Saat bilgisi yok'
              : todayHours.length ? `Bugün ${todayHours.map(h => `${formatTime(h.opensAt)}–${formatTime(h.closesAt)}`).join(', ')}`
                : 'Bugün kapalı'}
          </div>
          <div className="info-row"><Wallet size={18} />{formatCost(place.estimatedCost)}{place.estimatedCost ? ' kişi başı, tahmini' : ''}</div>
          {place.avgVisitMinutes && <div className="info-row"><Timer size={18} />Ortalama {place.avgVisitMinutes} dakika</div>}
        </div>

        {place.tags.length > 0 && (
          <div className="chips">
            {place.tags.map(t => <span key={t} className="badge">{TAG_LABELS[t] ?? t}</span>)}
          </div>
        )}

        <RouteMap height={180} points={[{ latitude: place.latitude, longitude: place.longitude, label: '•', title: place.name }]} />

        {place.openingHours.length > 0 && (
          <section className="card stack-sm">
            <h2 className="t-headline" style={{ marginBottom: 4 }}>Çalışma saatleri</h2>
            <table className="hours">
              <tbody>
                {DAY_NAMES.map((day, i) => {
                  const hours = place.openingHours.filter(h => h.dayOfWeek === i + 1)
                  return (
                    <tr key={day} className={i + 1 === today ? 'today' : ''}>
                      <th>{day}</th>
                      <td>{hours.length ? hours.map(h => `${formatTime(h.opensAt)} – ${formatTime(h.closesAt)}`).join(', ') : 'Kapalı'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </section>
        )}

        <p className="t-caption row" style={{ alignItems: 'flex-start' }}>
          <Info size={14} style={{ marginTop: 2, flexShrink: 0 }} />
          {place.lastVerifiedAt
            ? `Konum ve saatler ${new Date(place.lastVerifiedAt).toLocaleDateString('tr-TR', { day: 'numeric', month: 'long', year: 'numeric' })} tarihinde kontrol edildi. Fiyatlar tahminidir; saatler değişebilir.`
            : 'Fiyat ve saat bilgileri henüz doğrulanmadı; gitmeden önce kontrol etmeni öneririz.'}
        </p>
      </div>

      <div className="sticky-cta">
        <a className="btn btn-primary btn-lg grow" href={mapsUrl} target="_blank" rel="noreferrer">
          <Navigation size={18} /> Yol tarifi al
        </a>
      </div>
    </main>
  )
}
