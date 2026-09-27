import type { PlaceCategory, StopType, WalkingTolerance } from '../api/types'

export const CATEGORY_LABELS: Record<PlaceCategory, string> = {
  BREAKFAST: 'Kahvaltı',
  RESTAURANT: 'Restoran',
  CAFE: 'Kafe',
  DESSERT: 'Tatlı',
  ATTRACTION: 'Gezilecek yer',
  MUSEUM: 'Müze',
  PARK: 'Park',
  CULTURE: 'Kültür',
}

export const STOP_TYPE_LABELS: Record<StopType, string> = {
  BREAKFAST: 'Kahvaltı',
  SIGHTSEEING: 'Gezi',
  LUNCH: 'Öğle yemeği',
  COFFEE: 'Kahve',
  DESSERT: 'Tatlı',
  DINNER: 'Akşam yemeği',
}

export const WALKING_LABELS: Record<WalkingTolerance, string> = {
  LOW: 'Az yürüyelim',
  MEDIUM: 'Normal',
  HIGH: 'Bol yürüyüş',
}

// Same keys as the backend place tags
export const INTEREST_LABELS: Record<string, string> = {
  history: 'Tarih',
  museum: 'Müze',
  sea: 'Deniz',
  nature: 'Doğa',
  art: 'Sanat',
  'street-art': 'Sokak sanatı',
  view: 'Manzara',
  local: 'Yerel',
  books: 'Kitap',
  architecture: 'Mimari',
  seafood: 'Deniz ürünleri',
  budget: 'Uygun fiyat',
}

// Every place tag (interests + descriptive tags) for display
export const TAG_LABELS: Record<string, string> = {
  ...INTEREST_LABELS,
  traditional: 'Geleneksel',
  coffee: 'Kahve',
  dessert: 'Tatlı',
  breakfast: 'Kahvaltı',
  tea: 'Çay',
  walk: 'Yürüyüş',
  shopping: 'Alışveriş',
  music: 'Müzik',
  sports: 'Spor',
  landmark: 'Simge yapı',
  religious: 'İbadethane',
  theatre: 'Tiyatro',
  food: 'Yeme-içme',
  dinner: 'Akşam yemeği',
}

export const DAY_NAMES = ['Pzt', 'Sal', 'Çar', 'Per', 'Cum', 'Cmt', 'Paz']

// "09:30:00" -> "09:30"
export function formatTime(time: string): string {
  return time.slice(0, 5)
}

export function formatDistance(meters: number): string {
  if (meters < 1000) return `${Math.round(meters)} m`
  return `${(meters / 1000).toLocaleString('tr-TR', { maximumFractionDigits: 1 })} km`
}

export function formatCost(cost: number | null | undefined): string {
  if (!cost) return 'Ücretsiz'
  return `~${cost.toLocaleString('tr-TR')} TL`
}

export function formatDate(date: string): string {
  return new Date(`${date}T00:00:00`).toLocaleDateString('tr-TR', { day: 'numeric', month: 'long', weekday: 'long' })
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('tr-TR', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' })
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
