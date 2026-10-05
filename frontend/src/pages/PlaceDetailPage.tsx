import { Clock, ExternalLink, Globe, Heart, Info, MapPin, Navigation, Phone, Timer, Umbrella, Wallet } from 'lucide-react'
import { useParams } from 'react-router'
import { api } from '../api'
import { useGate } from '../components/gate'
import { OpenBadge, PhotoCredit, VerifiedBadge } from '../components/PlaceViews'
import { PlaceReportAndReview } from '../components/PlaceReview'
import { RouteMap } from '../components/RouteMap'
import { Alert, BackButton, ErrorState, Skeleton } from '../components/ui'
import { UserPhotosSection } from '../components/UserPhotos'
import { CATEGORY_ICON, Rating, usePlacePhoto } from '../components/visuals'
import { useSavedPlaces } from '../context/SavedPlacesContext'
import { CATEGORY_LABELS, dayNames, formatCost, formatTime, googleMapsPinUrl, googleMapsSearchUrl, httpUrl, TAG_LABELS } from '../lib/format'
import { useAsync } from '../lib/useAsync'
import { locale, useT } from '../lib/i18n'

export function PlaceDetailPage() {
  const { id } = useParams()
  const saved = useSavedPlaces()
  const gate = useGate()
  const t = useT()
  const { data: place, error, loading, reload } = useAsync(() => api.place(Number(id)), [id])
  // Is it still there (Google Places)? Never blocks the page: without an answer the search around our pin is used
  const { data: availability } = useAsync(() => api.placeAvailability(Number(id)).catch(() => null), [id])
  const photo = usePlacePhoto(place?.image)

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
    return <main className="screen"><BackButton /><ErrorState message={error ?? t('Mekan bulunamadı', 'Place not found')} onRetry={reload} /></main>
  }

  const Icon = CATEGORY_ICON[place.category]
  const isSaved = saved.isSaved(place.id)
  const today = (new Date().getDay() + 6) % 7 + 1
  const todayHours = place.openingHours.filter(h => h.dayOfWeek === today)
  // The backend may know the exact place; otherwise a search around our pin. Only http(s) links are followed
  const mapsUrl = httpUrl(availability?.mapsUrl) ?? googleMapsPinUrl(place)
  const closed = availability?.status === 'CLOSED_PERMANENTLY'
  // No match on Google, or the owner marked it "may have closed"
  const notFound = availability?.status === 'NOT_FOUND' || Boolean(place.suspect)

  return (
    <main className="screen screen-no-tabbar" style={{ paddingBottom: 'calc(var(--safe-bottom) + 110px)' }}>
      <div className={`place-hero ${photo.url ? 'place-hero-photo' : ''}`}>
        <div className={`tile tile-${place.category}`}>
          <span className="hero-icon"><Icon size={44} strokeWidth={1.6} /></span>
          {photo.url && <img className="tile-photo" src={photo.url} alt={place.name} decoding="async" onError={photo.onError} />}
        </div>
        <div className="place-hero-bar">
          <BackButton glass />
          <button className="icon-btn icon-btn-glass" aria-pressed={isSaved} aria-label={isSaved ? t('Kaydedilenlerden çıkar', 'Remove from saved') : t('Kaydet', 'Save')}
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
            {place.verified && <VerifiedBadge />}
            {place.indoor ? <span className="badge badge-sea"><Umbrella size={12} /> {t('Kapalı alan', 'Indoor')}</span>
              : <span className="badge">{t('Açık alan', 'Outdoor')}</span>}
          </div>
        </div>

        {place.description && <p className="ink-2">{place.description}</p>}

        <div className="card" style={{ padding: '4px 16px' }}>
          {place.address && <div className="info-row"><MapPin size={18} />{place.address}</div>}
          <div className="info-row">
            <Clock size={18} />
            {place.openingHours.length === 0 ? t('Çalışma saati bilgisi yok', 'Opening hours unknown')
              : todayHours.length ? `${t('Bugün', 'Today')} ${todayHours.map(h => `${formatTime(h.opensAt)}–${formatTime(h.closesAt)}`).join(', ')}`
                : t('Bugün kapalı', 'Closed today')}
          </div>
          <div className="info-row"><Wallet size={18} />{formatCost(place.estimatedCost)}{place.estimatedCost != null && place.estimatedCost > 0 ? t(' kişi başı, tahmini', ' per person, estimated') : ''}</div>
          {place.phone && (
            <div className="info-row"><Phone size={18} /><a href={`tel:${place.phone.replace(/[^+\d]/g, '')}`}>{place.phone}</a></div>
          )}
          {httpUrl(place.website) && (
            <div className="info-row">
              <Globe size={18} />
              <a href={httpUrl(place.website)!} target="_blank" rel="noopener noreferrer">{t('Web sitesi', 'Website')} <ExternalLink size={12} /></a>
            </div>
          )}
          {place.avgVisitMinutes != null && place.avgVisitMinutes > 0 && <div className="info-row"><Timer size={18} />{t(`Ortalama ${place.avgVisitMinutes} dakika`, `About ${place.avgVisitMinutes} minutes`)}</div>}
        </div>

        {place.tags.length > 0 && (
          <div className="chips">
            {place.tags.map(tag => <span key={tag} className="badge">{TAG_LABELS[tag] ?? tag}</span>)}
          </div>
        )}

        <RouteMap height={180} points={[{ latitude: place.latitude, longitude: place.longitude, label: '•', title: place.name }]} />

        <UserPhotosSection target={{ type: 'PLACE', id: place.id }} name={place.name}
                           title={t('Kullanıcılarımızdan fotoğraflar', 'Photos from our users')}
                           mapsUrl={googleMapsSearchUrl(place.name, place.district, place.city)} />

        {place.openingHours.length > 0 && (
          <section className="card stack-sm">
            <h2 className="t-headline" style={{ marginBottom: 4 }}>{t('Çalışma saatleri', 'Opening hours')}</h2>
            <table className="hours">
              <tbody>
                {dayNames().map((day, i) => {
                  const hours = place.openingHours.filter(h => h.dayOfWeek === i + 1)
                  return (
                    <tr key={day} className={i + 1 === today ? 'today' : ''}>
                      <th>{day}</th>
                      <td>{hours.length ? hours.map(h => `${formatTime(h.opensAt)} – ${formatTime(h.closesAt)}`).join(', ') : t('Kapalı', 'Closed')}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </section>
        )}

        {photo.url && place.image && <PhotoCredit image={place.image} />}

        <p className="t-caption row" style={{ alignItems: 'flex-start' }}>
          <Info size={14} style={{ marginTop: 2, flexShrink: 0 }} />
          {!place.verified && place.source === 'OVERTURE' ? (
            <span>
              {t('Bu mekanın bilgileri Overture Maps’ten (Foursquare, Meta ve diğer kaynaklar) geliyor ve güncel olmayabilir. Konumu işletmenin kendi sayfasından geldiği için haritada biraz kayabilir.', 'This place’s details come from Overture Maps (Foursquare, Meta and other sources) and may be out of date. Its pin comes from the business’ own page and can be a little off.')}
            </span>
          ) : !place.verified ? (
            <span>
              {t('Bu mekanın bilgileri OpenStreetMap katkıcılarından geliyor ve güncel olmayabilir.', 'This place’s details come from OpenStreetMap contributors and may be out of date.')}
              {httpUrl(place.sourceUrl) && (
                <>
                  {' '}
                  <a href={httpUrl(place.sourceUrl)!} target="_blank" rel="noopener noreferrer" className="section-link" style={{ fontSize: 13 }}>
                    {t('OpenStreetMap’te gör', 'View on OpenStreetMap')} <ExternalLink size={12} />
                  </a>
                </>
              )}
            </span>
          ) : place.lastVerifiedAt
            ? t(
              `Konum ve saatler ${new Date(place.lastVerifiedAt).toLocaleDateString(locale(), { day: 'numeric', month: 'long', year: 'numeric' })} tarihinde kontrol edildi. Fiyatlar tahminidir; saatler değişebilir.`,
              `Location and hours checked on ${new Date(place.lastVerifiedAt).toLocaleDateString(locale(), { day: 'numeric', month: 'long', year: 'numeric' })}. Prices are estimates; hours may change.`,
            )
            : t('Fiyat ve saat bilgileri henüz doğrulanmadı; gitmeden önce kontrol etmeni öneririz.', 'Prices and hours are not verified yet; we suggest checking before you go.')}
        </p>

        <PlaceReportAndReview place={place} onChanged={reload} />
      </div>

      <div className="sticky-cta" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 10 }}>
        {closed && <Alert tone="danger">{t('Google Haritalar’a göre bu yer kalıcı olarak kapanmış. Artık Nomi’de önerilmeyecek.', 'According to Google Maps this place has closed for good. Nomi will not suggest it any more.')}</Alert>}
        {notFound && <Alert tone="warning">{place.suspect
          ? t('Bu yer kapanmış olabilir.', 'This place may have closed.')
          : t('Bu yer ile eşleşen bir konum bulunamadı. Kapanmış olabilir.', 'No matching place was found here. It may have closed.')}</Alert>}
        {availability?.status === 'CLOSED_TEMPORARILY' && <Alert tone="warning">{t('Google Haritalar’a göre bu yer geçici olarak kapalı.', 'According to Google Maps this place is temporarily closed.')}</Alert>}
        {!closed && (
          <a className={`btn ${notFound ? 'btn-secondary' : 'btn-primary'} btn-lg grow`} href={mapsUrl} target="_blank" rel="noreferrer">
            <Navigation size={18} /> {notFound ? t('Haritada yine de ara', 'Search the map anyway') : t('Google Haritalar’da aç', 'Open in Google Maps')}
          </a>
        )}
      </div>
    </main>
  )
}
