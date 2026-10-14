import {
  CakeSlice, Camera, CloudFog, CloudLightning, CloudRain, CloudSun, Coffee, Croissant, Drama, Landmark, Moon,
  MoonStar, ShoppingBasket, Snowflake, Star, Sun, Trees, UtensilsCrossed, type LucideIcon,
} from 'lucide-react'
import { useState } from 'react'
import type { PlaceCategory, PlaceImage, StopType } from '../api/types'
import logoWordmark from '../assets/logo-wordmark.png'
import { httpUrl } from '../lib/format'

export const CATEGORY_ICON: Record<PlaceCategory, LucideIcon> = {
  BREAKFAST: Croissant,
  RESTAURANT: UtensilsCrossed,
  CAFE: Coffee,
  DESSERT: CakeSlice,
  ATTRACTION: Camera,
  MUSEUM: Landmark,
  PARK: Trees,
  CULTURE: Drama,
  WORSHIP: MoonStar,
  MARKET: ShoppingBasket,
}

export const STOP_ICON: Record<StopType, LucideIcon> = {
  BREAKFAST: Croissant,
  SIGHTSEEING: Camera,
  LUNCH: UtensilsCrossed,
  COFFEE: Coffee,
  DESSERT: CakeSlice,
  DINNER: Moon,
}

export const WEATHER_ICON: Record<string, LucideIcon> = {
  CLEAR: Sun,
  CLOUDY: CloudSun,
  FOG: CloudFog,
  RAIN: CloudRain,
  SNOW: Snowflake,
  STORM: CloudLightning,
}

// The photo to show, or null when there is none, it is not http(s) or it failed to load
export function usePlacePhoto(image: PlaceImage | null | undefined) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const url = httpUrl(image?.url)
  return {
    url: url && url !== failedUrl ? url : null,
    onError: () => setFailedUrl(url),
  }
}

// Gradient tile with the category icon; covered by the place photo when there is one.
// The tile keeps its fixed size, so a late or failed photo never shifts the layout.
export function CategoryTile({ category, size = 28, className = '', image, alt }: {
  category: PlaceCategory
  size?: number
  className?: string
  image?: PlaceImage | null
  alt?: string
}) {
  const Icon = CATEGORY_ICON[category]
  const photo = usePlacePhoto(image)
  return (
    <div className={`tile tile-${category} ${className}`} aria-hidden={photo.url ? undefined : true}>
      <Icon size={size} strokeWidth={1.8} style={{ position: 'relative', zIndex: 1 }} />
      {photo.url && (
        <img className="tile-photo" src={photo.url} alt={alt ?? ''} loading="lazy" decoding="async" onError={photo.onError} />
      )}
    </div>
  )
}

export function Rating({ value }: { value: number | null }) {
  if (value == null) return null
  return (
    <span className="rating">
      <Star size={13} strokeWidth={0} />
      {value.toFixed(1)}
    </span>
  )
}

// The Nomi wordmark (built from assets/logo-source.png by scripts/logo.mjs); white in dark mode (styles.css)
export function BrandMark() {
  return (
    <span className="brand-mark">
      <img className="brand-wordmark" src={logoWordmark} alt="Nomi" width={81} height={40} />
    </span>
  )
}
