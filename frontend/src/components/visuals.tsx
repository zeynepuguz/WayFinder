import {
  CakeSlice, Camera, CloudFog, CloudLightning, CloudRain, CloudSun, Coffee, Croissant, Drama, Landmark, Moon,
  Snowflake, Star, Sun, Trees, UtensilsCrossed, type LucideIcon,
} from 'lucide-react'
import type { PlaceCategory, StopType } from '../api/types'

export const CATEGORY_ICON: Record<PlaceCategory, LucideIcon> = {
  BREAKFAST: Croissant,
  RESTAURANT: UtensilsCrossed,
  CAFE: Coffee,
  DESSERT: CakeSlice,
  ATTRACTION: Camera,
  MUSEUM: Landmark,
  PARK: Trees,
  CULTURE: Drama,
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

// Gradient tile with the category icon: a consistent stand-in until places have photos
export function CategoryTile({ category, size = 28, className = '' }: {
  category: PlaceCategory
  size?: number
  className?: string
}) {
  const Icon = CATEGORY_ICON[category]
  return (
    <div className={`tile tile-${category} ${className}`} aria-hidden>
      <Icon size={size} strokeWidth={1.8} style={{ position: 'relative', zIndex: 1 }} />
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

export function BrandMark() {
  return (
    <span className="brand-mark">
      <span className="brand-logo" aria-hidden>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"
             strokeLinecap="round" strokeLinejoin="round">
          <path d="M12 2 15 9 22 12 15 15 12 22 9 15 2 12 9 9Z" />
        </svg>
      </span>
      Nomi
    </span>
  )
}
