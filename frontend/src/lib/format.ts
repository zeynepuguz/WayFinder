import type { PlaceCategory, RouteStatus, StopType, WalkingTolerance } from '../api/types'
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
  WORSHIP: ['İbadet', 'Worship'],
  MARKET: ['Market', 'Groceries'],
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
  quick: ['Hızlı yemek', 'Quick bites'],
  bakery: ['Fırın', 'Bakery'],
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

// null = price unknown (most OpenStreetMap places), 0 = free
export function formatCost(cost: number | null | undefined): string {
  if (cost == null) return tr('Fiyat bilgisi yok', 'No price info')
  if (cost === 0) return tr('Ücretsiz', 'Free')
  return `~${cost.toLocaleString(locale())} TL`
}

// A route's spend: "~0 TL" would look free when no stop has a known price (most imported places)
export function routeCost(total: number, known: boolean | undefined): string {
  return known === false ? tr('Fiyat bilgisi yok', 'No price info') : `~${total.toLocaleString(locale())} TL`
}

// Price filter: a place with an unknown price never counts as "within budget"
export function withinBudget(cost: number | null | undefined, max: number): boolean {
  return cost != null && cost <= max
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

// A route that can still be walked and changed (planned or in progress)
export function isOpenRoute(status: RouteStatus): boolean {
  return status === 'DRAFT' || status === 'ACTIVE'
}

// The route's day is over: read-only ("Geçmiş rotalar"). The backend marks such routes EXPIRED; the date check
// also covers a route read just after midnight
export function isPastRoute(route: { date: string; status: RouteStatus }): boolean {
  return route.status === 'EXPIRED' || route.date < todayIso()
}

// Great-circle distance on the client (live map: distance to a pin, when to refresh suggestions)
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
  WORSHIP: 'ibadet',
  MARKET: 'market',
}

export const CATEGORY_BY_SLUG: Record<string, PlaceCategory> = Object.fromEntries(
  Object.entries(CATEGORY_SLUGS).map(([category, slug]) => [slug, category as PlaceCategory]),
)

// Only http(s) links from the API are rendered as links or images
export function httpUrl(url: string | null | undefined): string | null {
  return url && /^https?:\/\//i.test(url) ? url : null
}

// "kadikoy" matches "Kadıköy": case- and diacritic-insensitive, so English keyboards work too
export function fold(text: string): string {
  return text.toLocaleLowerCase('tr').replace(/ı/g, 'i').normalize('NFD').replace(/[̀-ͯ]/g, '')
}

// Appends the next page of a list; the same place can move between pages while data changes, so ids stay unique
export function appendUnique<T extends { id: number }>(current: T[], next: T[]): T[] {
  const seen = new Set(current.map(item => item.id))
  const added = next.filter(item => !seen.has(item.id) && (seen.add(item.id), true))
  return added.length ? [...current, ...added] : current
}

const BACK_VOWELS = 'aıou'
const FRONT_VOWELS = 'eiöü'
// f, s, t, k, ç, ş, h, p ("FıSTıKÇı ŞaHaP") harden the suffix: -ta/-te
const HARD_CONSONANTS = 'fstkçşhp'

/**
 * Turkish locative with the apostrophe used for proper names:
 * İstanbul’da, İzmir’de, Muş’ta, Gaziantep’te. The last vowel picks a/e (vowel harmony),
 * a hard final consonant turns d into t.
 */
export function locativeTr(name: string): string {
  const word = name.trim()
  return `${word}’${caseSuffix(word)}`
}

/** Turkish ablative for proper names, same harmony: Kadıköy’den, Beşiktaş’tan, Çankaya’dan, Gaziantep’ten. */
export function ablativeTr(name: string): string {
  const word = name.trim()
  return `${word}’${caseSuffix(word)}n`
}

// "da/de/ta/te": the last vowel picks a/e, a hard final consonant turns d into t
function caseSuffix(word: string): string {
  const lower = word.toLocaleLowerCase('tr')
  let vowel = 'a'
  for (let i = lower.length - 1; i >= 0; i--) {
    if (BACK_VOWELS.includes(lower[i])) break
    if (FRONT_VOWELS.includes(lower[i])) {
      vowel = 'e'
      break
    }
  }
  const consonant = HARD_CONSONANTS.includes(lower[lower.length - 1] ?? '') ? 't' : 'd'
  return consonant + vowel
}

/** Google Maps search for a place or an area ("Moda Kahve, Kadıköy İstanbul"); empty parts are left out. */
export function googleMapsSearchUrl(name: string, ...area: (string | null | undefined)[]): string {
  const where = area.map(part => part?.trim()).filter(Boolean).join(' ')
  const query = [name.trim(), where].filter(Boolean).join(', ')
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(query)}`
}

/**
 * Cost line of a ready-made route: the sum of the known prices, never a guess for the rest.
 * No known price at all -> "Fiyat bilgisi yok"; some unknown -> the known sum plus how many are missing.
 */
export function popularRouteCost(cost: number | null, unknownPriceStops: number): string {
  if (cost == null) return tr('Fiyat bilgisi yok', 'No price info')
  const amount = `~${cost.toLocaleString(locale())} TL`
  const known = cost === 0 ? tr('Ücretsiz', 'Free') : tr(`Kişi başı ${amount}`, `${amount} per person`)
  if (unknownPriceStops <= 0) return known
  return `${known} · ${tr(`${unknownPriceStops} durağın fiyatı bilinmiyor`,
    `${unknownPriceStops === 1 ? '1 stop has' : `${unknownPriceStops} stops have`} no price info`)}`
}
