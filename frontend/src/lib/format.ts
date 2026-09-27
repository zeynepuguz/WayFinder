import type { PlaceCategory, StopType, WalkingTolerance } from '../api/types'
import { bilingual, locale, tr } from './i18n'

export const CATEGORY_LABELS: Record<PlaceCategory, string> = bilingual({
  BREAKFAST: ['Kahvaltı', 'Breakfast'],
  RESTAURANT: ['Restoran', 'Restaurant'],
  CAFE: ['Kafe', 'Café'],
  DESSERT: ['Tatlı', 'Dessert'],
  ATTRACTION: ['Gezilecek yer', 'Sights'],
  MUSEUM: ['Müze', 'Museum'],
  PARK: ['Park', 'Park'],
  CULTURE: ['Kültür', 'Culture'],
})

export const STOP_TYPE_LABELS: Record<StopType, string> = bilingual({
  BREAKFAST: ['Kahvaltı', 'Breakfast'],
  SIGHTSEEING: ['Gezi', 'Sightseeing'],
  LUNCH: ['Öğle yemeği', 'Lunch'],
  COFFEE: ['Kahve', 'Coffee'],
  DESSERT: ['Tatlı', 'Dessert'],
  DINNER: ['Akşam yemeği', 'Dinner'],
})

export const WALKING_LABELS: Record<WalkingTolerance, string> = bilingual({
  LOW: ['Az yürüyelim', 'Little walking'],
  MEDIUM: ['Normal', 'Normal'],
  HIGH: ['Bol yürüyüş', 'Lots of walking'],
})

const INTERESTS = {
  history: ['Tarih', 'History'],
  museum: ['Müze', 'Museums'],
  sea: ['Deniz', 'Seaside'],
  nature: ['Doğa', 'Nature'],
  art: ['Sanat', 'Art'],
  'street-art': ['Sokak sanatı', 'Street art'],
  view: ['Manzara', 'Views'],
  local: ['Yerel', 'Local'],
  books: ['Kitap', 'Books'],
  architecture: ['Mimari', 'Architecture'],
  seafood: ['Deniz ürünleri', 'Seafood'],
  budget: ['Uygun fiyat', 'Budget'],
} satisfies Record<string, [string, string]>

// Same keys as the backend place tags
export const INTEREST_LABELS: Record<string, string> = bilingual<string>(INTERESTS)

// Every place tag (interests + descriptive tags) for display
export const TAG_LABELS: Record<string, string> = bilingual<string>({
  ...INTERESTS,
  traditional: ['Geleneksel', 'Traditional'],
  coffee: ['Kahve', 'Coffee'],
  dessert: ['Tatlı', 'Dessert'],
  breakfast: ['Kahvaltı', 'Breakfast'],
  tea: ['Çay', 'Tea'],
  walk: ['Yürüyüş', 'Walk'],
  shopping: ['Alışveriş', 'Shopping'],
  music: ['Müzik', 'Music'],
  sports: ['Spor', 'Sports'],
  landmark: ['Simge yapı', 'Landmark'],
  religious: ['İbadethane', 'Place of worship'],
  theatre: ['Tiyatro', 'Theatre'],
  food: ['Yeme-içme', 'Food'],
  dinner: ['Akşam yemeği', 'Dinner'],
})

const DAYS = {
  tr: ['Pzt', 'Sal', 'Çar', 'Per', 'Cum', 'Cmt', 'Paz'],
  en: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'],
}

// Short day names, Monday first like the backend's day_of_week
export function dayNames(): string[] {
  return tr('tr', 'en') === 'en' ? DAYS.en : DAYS.tr
}

// "09:30:00" -> "09:30"
export function formatTime(time: string): string {
  return time.slice(0, 5)
}

export function formatDistance(meters: number): string {
  if (meters < 1000) return `${Math.round(meters)} m`
  return `${(meters / 1000).toLocaleString(locale(), { maximumFractionDigits: 1 })} km`
}

export function formatCost(cost: number | null | undefined): string {
  if (!cost) return tr('Ücretsiz', 'Free')
  return `~${cost.toLocaleString(locale())} TL`
}

export function formatDate(date: string): string {
  return new Date(`${date}T00:00:00`).toLocaleDateString(locale(), { day: 'numeric', month: 'long', weekday: 'long' })
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString(locale(), { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' })
}

export function todayIso(): string {
  const now = new Date()
  const offset = now.getTimezoneOffset() * 60000
  return new Date(now.getTime() - offset).toISOString().slice(0, 10)
}

// Great-circle distance, only used on the client to decide if the user is inside the MVP area
export function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const toRad = (d: number) => (d * Math.PI) / 180
  const dLat = toRad(lat2 - lat1)
  const dLon = toRad(lon2 - lon1)
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2
  return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

// URL slugs of the crawlable category pages (/kadikoy/kafe); keep in sync with scripts/prerender.mjs
export const CATEGORY_SLUGS: Record<PlaceCategory, string> = {
  BREAKFAST: 'kahvalti',
  RESTAURANT: 'restoran',
  CAFE: 'kafe',
  DESSERT: 'tatli',
  ATTRACTION: 'gezilecek-yerler',
  MUSEUM: 'muze',
  PARK: 'park',
  CULTURE: 'kultur',
}

export const CATEGORY_BY_SLUG: Record<string, PlaceCategory> = Object.fromEntries(
  Object.entries(CATEGORY_SLUGS).map(([category, slug]) => [slug, category as PlaceCategory]),
)
