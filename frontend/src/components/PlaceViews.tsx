import { Award, BadgeCheck, Bookmark, Camera, ExternalLink, MapPin } from 'lucide-react'
import { Link } from 'react-router'
import type { Place, PlaceImage, Recommendation } from '../api/types'
import { useSavedPlaces } from '../context/SavedPlacesContext'
import { formatCost, formatDistance, httpUrl, shownRating , placeKindLabel } from '../lib/format'
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
      <Bookmark size={19} fill={isSaved ? 'currentColor' : 'none'} />
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

// One of the area's favourites: well rated by enough Nomi users, or saved by many
export function FavoriteBadge() {
  const t = useT()
  return <span className="badge badge-stamp"><Award size={12} /> {t('Bölgenin favorisi', 'Local favourite')}</span>
}

// Compact row for lists
// showDistrict: city-wide lists add the district ("Kafe · Üsküdar") so same-named places can be told apart
// showVotes: the users' own average and vote count ("★ 4.6 · 12 oy"), for lists ranked by them (Favori mekanlar)
export function PlaceRow({ place, showDistrict, showVotes }: { place: Place; showDistrict?: boolean; showVotes?: boolean }) {
  const t = useT()
  const votes = place.userRatingCount ?? 0
  return (
    <div className="card" style={{ padding: 0 }}>
      <Link to={`/places/${place.id}`} className="place-row card-press">
        <CategoryTile category={place.category} image={place.image} alt={place.name} />
        <div className="place-row-body">
          <span className="t-overline place-kind">{placeKindLabel(place)}{showDistrict && place.district ? ` · ${place.district}` : ''}</span>
          <span className="place-name" style={{ paddingRight: 36 }}>{place.name}</span>
          <div className="meta">
            {showVotes && place.userRating != null
              ? <span><Rating value={place.userRating} /> {t(`· ${votes} oy`, `· ${votes} ${votes === 1 ? 'vote' : 'votes'}`)}</span>
              : <Rating value={shownRating(place)} />}
            {place.distanceMeters != null && <span><MapPin size={12} />{formatDistance(place.distanceMeters)}</span>}
          </div>
          <div className="row wrap" style={{ gap: 6 }}>
            {place.favorite && <FavoriteBadge />}
            <OpenBadge openNow={place.openNow} />
            <span className="badge">{formatCost(place.estimatedCost)}</span>
            {place.indoor && <span className="badge badge-sea">{t('Kapalı alan', 'Indoor')}</span>}
          </div>
        </div>
        <SaveButton place={place} />
      </Link>
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
          <span className="t-caption place-card-kind">{placeKindLabel(place)}</span>
          <div className="meta">
            <Rating value={shownRating(place)} />
            <span>{formatCost(place.estimatedCost)}</span>
            {place.distanceMeters != null && <span>{formatDistance(place.distanceMeters)}</span>}
          </div>
          {reason && <span className="t-caption place-card-reason" title={reason}>{reason}</span>}
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
