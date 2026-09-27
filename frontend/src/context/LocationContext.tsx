import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { haversineMeters } from '../lib/format'

// MVP data only covers Kadıköy. Outside of it we use a demo location so the app still works.
export const KADIKOY = { latitude: 40.991, longitude: 29.023 }
const MVP_RADIUS_METERS = 15000

export type LocationSource = 'gps' | 'demo-outside' | 'demo-denied' | 'loading'

interface LocationState {
  latitude: number
  longitude: number
  source: LocationSource
  refresh: () => void
}

const LocationContext = createContext<LocationState | null>(null)

export function LocationProvider({ children }: { children: ReactNode }) {
  const [position, setPosition] = useState({ ...KADIKOY, source: 'loading' as LocationSource })

  const refresh = useCallback(() => {
    if (!('geolocation' in navigator)) {
      setPosition({ ...KADIKOY, source: 'demo-denied' })
      return
    }
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        const inside = haversineMeters(coords.latitude, coords.longitude, KADIKOY.latitude, KADIKOY.longitude)
          <= MVP_RADIUS_METERS
        setPosition(inside
          ? { latitude: coords.latitude, longitude: coords.longitude, source: 'gps' }
          : { ...KADIKOY, source: 'demo-outside' })
      },
      () => setPosition({ ...KADIKOY, source: 'demo-denied' }),
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 },
    )
  }, [])

  useEffect(refresh, [refresh])

  const value = useMemo(() => ({ ...position, refresh }), [position, refresh])
  return <LocationContext.Provider value={value}>{children}</LocationContext.Provider>
}

export function useUserLocation(): LocationState {
  const context = useContext(LocationContext)
  if (!context) throw new Error('useUserLocation must be used inside LocationProvider')
  return context
}

export function locationLabel(source: LocationSource): string {
  switch (source) {
    case 'gps':
      return 'Konumun kullanılıyor'
    case 'demo-outside':
      return 'Nomi şimdilik sadece Kadıköy’de: demo konum kullanılıyor'
    case 'demo-denied':
      return 'Konum izni yok: Kadıköy iskelesi kullanılıyor'
    default:
      return 'Konum alınıyor…'
  }
}
