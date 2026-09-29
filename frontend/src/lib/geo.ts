import type { MapBox, PlaceCategory } from '../api/types'

// Türkiye, roughly (Edirne/Gökçeada in the west to Iğdır in the east): Nomi's service area
export const TURKEY_BOUNDS: MapBox = { south: 35.8, west: 25.6, north: 42.2, east: 44.9 }

export function inBox(box: MapBox, latitude: number, longitude: number): boolean {
  return latitude >= box.south && latitude <= box.north && longitude >= box.west && longitude <= box.east
}

export function isInTurkey(latitude: number, longitude: number): boolean {
  return inBox(TURKEY_BOUNDS, latitude, longitude)
}

// Below this zoom a screen covers too much of the city: the map asks the user to zoom in instead
export const MIN_PLACES_ZOOM = 13
// The backend accepts at most 0.6° per side; stay a little under it so rounding never trips the limit
export const MAX_BOX_SPAN = 0.59
export const MAX_MAP_PINS = 300

/** Shrinks a box around its center so neither side is longer than maxSpan degrees. */
export function clampBox(box: MapBox, maxSpan = MAX_BOX_SPAN): MapBox {
  const round = (n: number) => Math.round(n * 1e6) / 1e6
  let { south, west, north, east } = box
  if (north - south > maxSpan) {
    const mid = (north + south) / 2
    south = mid - maxSpan / 2
    north = mid + maxSpan / 2
  }
  if (east - west > maxSpan) {
    const mid = (east + west) / 2
    west = mid - maxSpan / 2
    east = mid + maxSpan / 2
  }
  return { south: round(south), west: round(west), north: round(north), east: round(east) }
}

/** The box to load places for, or null when the map is zoomed out too far. */
export function placesQueryBox(zoom: number, box: MapBox): MapBox | null {
  if (zoom < MIN_PLACES_ZOOM) return null
  return clampBox(box)
}

/**
 * Remembers only the newest request: starting one aborts the previous, and isLatest()
 * tells an answer that arrives out of order (the map has moved on since) to be ignored.
 */
export function latestRequest() {
  let current = 0
  let controller: AbortController | null = null
  return {
    start(): { id: number; signal: AbortSignal } {
      controller?.abort()
      controller = new AbortController()
      current += 1
      return { id: current, signal: controller.signal }
    },
    isLatest(id: number): boolean {
      return id === current
    },
    cancel() {
      controller?.abort()
      controller = null
      current += 1
    },
  }
}

// Map pin colour and symbol per category (same hues as the category tiles)
export const CATEGORY_PIN: Record<PlaceCategory, { color: string; symbol: string }> = {
  BREAKFAST: { color: '#e07b00', symbol: '🥐' },
  RESTAURANT: { color: '#e8452a', symbol: '🍽' },
  CAFE: { color: '#6f4e37', symbol: '☕' },
  DESSERT: { color: '#d02c82', symbol: '🍰' },
  ATTRACTION: { color: '#0077c2', symbol: '📷' },
  MUSEUM: { color: '#6938ef', symbol: '🏛' },
  PARK: { color: '#038a4f', symbol: '🌳' },
  CULTURE: { color: '#0e8579', symbol: '🎭' },
  WORSHIP: { color: '#1f6f5c', symbol: '🕌' },
  MARKET: { color: '#5a7d1a', symbol: '🛒' },
}
