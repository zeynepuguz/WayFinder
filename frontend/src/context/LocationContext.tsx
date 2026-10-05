import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { tr } from '../lib/i18n'
import { isInTurkey } from '../lib/geo'

// Nomi covers Türkiye. Outside of it (or without a fix) we use a demo location in Kadıköy so the app still works.
export const KADIKOY = { latitude: 40.991, longitude: 29.023 }

// demo-denied: the user refused location; demo-unavailable: allowed but no fix (indoors, GPS off, timeout)
type LocationSource = 'gps' | 'demo-outside' | 'demo-denied' | 'demo-unavailable' | 'loading'

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
    const onPosition = ({ coords }: GeolocationPosition) => {
      setPosition(isInTurkey(coords.latitude, coords.longitude)
        ? { latitude: coords.latitude, longitude: coords.longitude, source: 'gps' }
        : { ...KADIKOY, source: 'demo-outside' })
    }
    const onFinalError = (error: GeolocationPositionError) =>
      setPosition({ ...KADIKOY, source: error.code === error.PERMISSION_DENIED ? 'demo-denied' : 'demo-unavailable' })

    // GPS often has no fix indoors: fall back to a network (Wi-Fi/cell) position before giving up
    navigator.geolocation.getCurrentPosition(
      onPosition,
      error => {
        if (error.code === error.PERMISSION_DENIED) {
          onFinalError(error)
          return
        }
        navigator.geolocation.getCurrentPosition(onPosition, onFinalError,
          { enableHighAccuracy: false, timeout: 10000, maximumAge: 5 * 60000 })
      },
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
      return tr('Konumun kullanılıyor', 'Using your location')
    case 'demo-outside':
      return tr('Nomi Türkiye’de hizmet veriyor: demo konum kullanılıyor', 'Nomi covers Türkiye: using a demo location')
    case 'demo-denied':
      return tr('Konum izni yok: Kadıköy iskelesi kullanılıyor', 'No location permission: using Kadıköy pier')
    case 'demo-unavailable':
      return tr('Konum alınamadı: Kadıköy iskelesi kullanılıyor', 'Couldn’t get your location: using Kadıköy pier')
    default:
      return tr('Konum alınıyor…', 'Getting your location…')
  }
}

// ---------- live tracking (map) ----------

type LiveStatus = 'off' | 'locating' | 'live' | 'denied' | 'unavailable'

interface LiveLocation {
  latitude: number | null
  longitude: number | null
  // metres (68% confidence radius from the browser)
  accuracy: number | null
  status: LiveStatus
}

const NO_FIX: LiveLocation = { latitude: null, longitude: null, accuracy: null, status: 'off' }

/**
 * Follows the device position while `enabled` (the live map). The rest of the app uses the
 * one-shot useUserLocation(); this keeps GPS running only while a map is on screen.
 */
export function useLiveLocation(enabled: boolean): LiveLocation {
  const [live, setLive] = useState<LiveLocation>(NO_FIX)

  useEffect(() => {
    if (!enabled) {
      setLive(NO_FIX)
      return
    }
    if (!('geolocation' in navigator)) {
      setLive({ ...NO_FIX, status: 'unavailable' })
      return
    }
    setLive(current => ({ ...current, status: current.latitude == null ? 'locating' : current.status }))
    const id = navigator.geolocation.watchPosition(
      ({ coords }) => setLive({ latitude: coords.latitude, longitude: coords.longitude, accuracy: coords.accuracy, status: 'live' }),
      error => {
        if (error.code === error.PERMISSION_DENIED) {
          setLive({ ...NO_FIX, status: 'denied' })
          navigator.geolocation.clearWatch(id)
        } else {
          // Timeout / no fix: keep the last known position (if any) and keep watching
          setLive(current => ({ ...current, status: current.latitude == null ? 'unavailable' : current.status }))
        }
      },
      { enableHighAccuracy: true, maximumAge: 5000, timeout: 20000 },
    )
    return () => navigator.geolocation.clearWatch(id)
  }, [enabled])

  return live
}
