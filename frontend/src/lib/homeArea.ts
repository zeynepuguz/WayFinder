import { useSyncExternalStore } from 'react'
import type { City, District } from '../api/types'

// The place Ana Sayfa is about: null = the device location, or a city / one of its districts the user picked on
// Ana Sayfa. The last choice is kept on the device (a reload or the next app start keeps it). Keşfet's city choice
// never changes it; it changes Keşfet once (the next time Keşfet opens it shows that place).
export interface HomeArea {
  city: City
  // null = the whole city ("Genel")
  district: District | null
}

const KEY = 'nomi.homeArea'

function saved(): HomeArea | null {
  try {
    const raw = localStorage.getItem(KEY)
    const area = raw ? (JSON.parse(raw) as HomeArea) : null
    return area && typeof area.city?.slug === 'string' ? { city: area.city, district: area.district ?? null } : null
  } catch {
    return null
  }
}

let current: HomeArea | null = saved()
const listeners = new Set<() => void>()
// What Keşfet should show the next time it opens: a place picked on Ana Sayfa, null = back to the device location,
// undefined = nothing new (Keşfet keeps its own choice)
let forExplore: HomeArea | null | undefined

export function setHomeArea(area: HomeArea | null) {
  current = area
  forExplore = area
  try {
    if (area) localStorage.setItem(KEY, JSON.stringify(area))
    else localStorage.removeItem(KEY)
  } catch {
    // private mode: kept until the app closes
  }
  listeners.forEach(listener => listener())
}

export function exploreFollowUp(): HomeArea | null | undefined {
  return forExplore
}

export function exploreFollowedUp() {
  forExplore = undefined
}

export function useHomeArea(): HomeArea | null {
  return useSyncExternalStore(
    listener => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => current,
  )
}

// Tests: the module as on a fresh start, reading what is saved on the device
export function reloadHomeArea() {
  current = saved()
  forExplore = undefined
}
