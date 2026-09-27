import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api } from '../api'
import type { Place } from '../api/types'
import { useAuth } from './AuthContext'

interface SavedPlacesState {
  places: Place[]
  isSaved: (id: number) => boolean
  toggle: (place: Place) => Promise<void>
}

const SavedPlacesContext = createContext<SavedPlacesState | null>(null)

// Keeps the saved place list in one place so every heart icon in the app stays in sync
export function SavedPlacesProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const [places, setPlaces] = useState<Place[]>([])

  useEffect(() => {
    if (!user) {
      setPlaces([])
      return
    }
    api.savedPlaces().then(setPlaces).catch(() => setPlaces([]))
  }, [user])

  const isSaved = useCallback((id: number) => places.some(p => p.id === id), [places])

  const toggle = useCallback(async (place: Place) => {
    if (places.some(p => p.id === place.id)) {
      setPlaces(current => current.filter(p => p.id !== place.id))
      await api.unsavePlace(place.id)
    } else {
      setPlaces(current => [place, ...current])
      await api.savePlace(place.id)
    }
  }, [places])

  const value = useMemo(() => ({ places, isSaved, toggle }), [places, isSaved, toggle])
  return <SavedPlacesContext.Provider value={value}>{children}</SavedPlacesContext.Provider>
}

export function useSavedPlaces(): SavedPlacesState {
  const context = useContext(SavedPlacesContext)
  if (!context) throw new Error('useSavedPlaces must be used inside SavedPlacesProvider')
  return context
}
