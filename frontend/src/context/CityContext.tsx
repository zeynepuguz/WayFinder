import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api } from '../api'
import type { City } from '../api/types'
import { useAsync } from '../lib/useAsync'
import { useUserLocation } from './LocationContext'

// The user's own choice survives restarts; a detected city is not saved (it follows the user)
const STORAGE_KEY = 'nomi.city'
// Fallback when there is no choice and no usable location (Kadıköy demo location lies in Istanbul)
export const DEFAULT_CITY = 'istanbul'

function savedCity(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function saveCity(slug: string) {
  try {
    localStorage.setItem(STORAGE_KEY, slug)
  } catch {
    // private mode etc.: chosen for this visit only
  }
}

interface CityState {
  // null while the list (or the detection) is still loading
  city: City | null
  // known before the list arrives (saved choice / detection), so screens can load right away
  citySlug: string | null
  // all 81 cities, empty until loaded; placeCount 0 = coming soon
  cities: City[]
  loading: boolean
  error: string | null
  reload: () => void
  setCity: (slug: string) => void
}

const CityContext = createContext<CityState | null>(null)

/**
 * The city the app is about. Order: the saved choice, else the city at the user's GPS position
 * (only when it already has places), else Istanbul.
 */
export function CityProvider({ children }: { children: ReactNode }) {
  const location = useUserLocation()
  const [chosen, setChosen] = useState<string | null>(savedCity)
  const [detected, setDetected] = useState<string | null>(null)
  const list = useAsync(() => api.cities(), [])

  useEffect(() => {
    if (chosen || detected || location.source === 'loading') return
    // Demo locations (outside Türkiye, no permission, no fix) are in Kadıköy
    if (location.source !== 'gps') {
      setDetected(DEFAULT_CITY)
      return
    }
    let cancelled = false
    api.cityAt(location.latitude, location.longitude)
      .then(city => {
        if (!cancelled) setDetected(city.placeCount > 0 ? city.slug : DEFAULT_CITY)
      })
      .catch(() => {
        if (!cancelled) setDetected(DEFAULT_CITY)
      })
    return () => {
      cancelled = true
    }
  }, [chosen, detected, location.source, location.latitude, location.longitude])

  const cities = list.data ?? []
  let citySlug = chosen ?? detected
  // A saved city that no longer exists (or has no places) falls back to Istanbul once the list is known
  if (citySlug && list.data && !list.data.some(c => c.slug === citySlug && c.placeCount > 0)) citySlug = DEFAULT_CITY
  const city = cities.find(c => c.slug === citySlug) ?? null

  const setCity = useCallback((slug: string) => {
    saveCity(slug)
    setChosen(slug)
  }, [])
  const reload = list.reload

  const value = useMemo<CityState>(() => ({
    city, citySlug, cities, loading: list.loading, error: list.error, reload: () => void reload(), setCity,
  }), [city, citySlug, list.data, list.loading, list.error, reload, setCity])
  return <CityContext.Provider value={value}>{children}</CityContext.Provider>
}

export function useCity(): CityState {
  const context = useContext(CityContext)
  if (!context) throw new Error('useCity must be used inside CityProvider')
  return context
}
