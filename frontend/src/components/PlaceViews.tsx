import { BadgeCheck, Camera, Check, ExternalLink, Heart, MapPin } from 'lucide-react'
import { Link } from 'react-router'
import type { Place, PlaceImage, Recommendation } from '../api/types'
import { useSavedPlaces } from '../context/SavedPlacesContext'
import { CATEGORY_LABELS, formatCost, formatDistance, httpUrl } from '../lib/format'
import { useT } from '../lib/i18n'
import { useGate } from './gate'
import { CategoryTile, Rating } from './visuals'

function SaveButton({ place, glass }: { place: Place; glass?: boolean }) {
  const saved = useSavedPlaces()
  const gate = useGate()
  const isSaved = saved.isSaved(place.id)
  const t = useT()

  return (
    <button
      className={`icon-btn save ${glass ? 'icon-btn-glass' : 'icon-btn-plain'}`}
      style={{ width: 38, height: 38 }}
      aria-pressed={isSaved}
      aria-label={isSaved ? t('Kaydedilenlerden çıkar', 'Remove from saved') : t('Kaydet', 'Save')}
      onClick={e => {
        e.preventDefault()
        if (gate()) void saved.toggle(place)
      }}
    >
      <Heart size={19} fill={isSaved ? 'currentColor' : 'none'} />
    </button>
  )
}

export function OpenBadge({ openNow }: { openNow: boolean | null }) {
  const t = useT()
  if (openNow == null) return null
  return <span className={`badge ${openNow ? 'badge-success' : 'badge-danger'}`}>{openNow ? t('Açık', 'Open') : t('Kapalı', 'Closed')}</span>
}

// Places checked by the Nomi team (OpenStreetMap imports are not)
export function VerifiedBadge() {
  const t = useT()
  return <span className="badge badge-success"><BadgeCheck size={12} /> {t('Doğrulandı', 'Verified')}</span>
}

// Compact row for lists
export function PlaceRow({ place, reasons }: { place: Place; reasons?: string[] }) {
  const t = useT()
  return (
    <div className="card" style={{ padding: 0 }}>
      <Link to={`/places/${place.id}`} className="place-row card-press">
        <CategoryTile category={place.category} image={place.image} alt={place.name} />
        <div className="place-row-body">
          <span className="place-name" style={{ paddingRight: 36 }}>{place.name}</span>
          <div className="meta">
            <Rating value={place.rating} />
            <span>{CATEGORY_LABELS[place.category]}</span>
            {place.distanceMeters != null && <span><MapPin size={12} />{formatDistance(place.distanceMeters)}</span>}
          </div>
          <div className="row" style={{ gap: 6 }}>
            <OpenBadge openNow={place.openNow} />
            <span className="badge">{formatCost(place.estimatedCost)}</span>
            {place.indoor && <span className="badge badge-sea">{t('Kapalı alan', 'Indoor')}</span>}
          </div>
        </div>
        <SaveButton place={place} />
      </Link>
      {reasons && reasons.length > 0 && (
        <ul className="reasons" style={{ padding: '0 16px 14px' }}>
          {reasons.map(r => <li key={r}><Check size={14} />{r}</li>)}
        </ul>
      )}
    </div>
  )
}

// Large card for horizontal carousels
export function PlaceCard({ place, reason }: { place: Place; reason?: string }) {
  return (
    <div className="place-card-wrap">
      <Link to={`/places/${place.id}`} className="card place-card card-press">
        <CategoryTile category={place.category} size={40} image={place.image} alt={place.name} />
        <div className="place-card-body">
          <span className="place-name">{place.name}</span>
          <div className="meta">
            <Rating value={place.rating} />
            <span>{formatCost(place.estimatedCost)}</span>
            {place.distanceMeters != null && <span>{formatDistance(place.distanceMeters)}</span>}
          </div>
          {reason && <span className="t-caption" style={{ color: 'var(--success)', fontWeight: 650 }}>{reason}</span>}
        </div>
      </Link>
      <SaveButton place={place} glass />
    </div>
  )
}

// Smaller row for a "better fit, but farther away" suggestion: distance line + why it is worth it
export function FartherPlaceRow({ recommendation }: { recommendation: Recommendation }) {
  const { place, reasons, whyBetter } = recommendation
  return (
    <Link to={`/places/${place.id}`} className="card farther-row card-press">
      <CategoryTile category={place.category} size={22} image={place.image} alt={place.name} />
      <div className="grow stack-sm" style={{ gap: 2 }}>
        <span className="place-name" style={{ fontSize: 15 }}>{place.name}</span>
        {reasons[0] && <span className="t-caption">{reasons[0]}</span>}
        {whyBetter && <span className="t-caption rec-why">{whyBetter}</span>}
      </div>
    </Link>
  )
}

// Credit line required by the photo's free license: "Fotoğraf: author · license · Wikimedia Commons"
export function PhotoCredit({ image }: { image: PlaceImage }) {
  const t = useT()
  const parts = [image.author, image.license].map(p => p?.trim()).filter((p): p is string => !!p)
  const text = `${t('Fotoğraf', 'Photo')}: ${[...parts, 'Wikimedia Commons'].join(' · ')}`
  const link = httpUrl(image.sourceUrl)
  return (
    <p className="t-caption row photo-credit">
      <Camera size={13} style={{ flexShrink: 0 }} />
      {link ? (
        <a href={link} target="_blank" rel="noopener noreferrer">{text} <ExternalLink size={11} /></a>
      ) : <span>{text}</span>}
    </p>
  )
}
