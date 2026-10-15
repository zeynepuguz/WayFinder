import { useSyncExternalStore } from 'react'
import type { City, District } from '../api/types'

// The place Ana Sayfa is about: null = the device location (every app start begins there), or a city / one of its
// districts the user picked on Ana Sayfa. Keşfet's city choice never changes it. Kept for this app run only.
export interface HomeArea {
  city: City
  // null = the whole city ("Genel")
  district: District | null
}

let current: HomeArea | null = null
const listeners = new Set<() => void>()

export function setHomeArea(area: HomeArea | null) {
  current = area
  listeners.forEach(listener => listener())
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
