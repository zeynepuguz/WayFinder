import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api } from '../api'
import type { Place } from '../api/types'
import { useToast } from '../components/ui'
import { errorMessage } from '../lib/format'
import { tr } from '../lib/i18n'
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
  const toast = useToast()
  const [places, setPlaces] = useState<Place[]>([])

  useEffect(() => {
    if (!user) {
      setPlaces([])
      return
    }
    api.savedPlaces().then(setPlaces).catch(() => setPlaces([]))
  }, [user])

  const isSaved = useCallback((id: number) => places.some(p => p.id === id), [places])

  // Optimistic: the heart changes at once; if the server says no, it changes back and the user is told
  const toggle = useCallback(async (place: Place) => {
    const without = (current: Place[]) => current.filter(p => p.id !== place.id)
    const withPlace = (current: Place[]) => (current.some(p => p.id === place.id) ? current : [place, ...current])
    const wasSaved = places.some(p => p.id === place.id)
    setPlaces(wasSaved ? without : withPlace)
    try {
      await (wasSaved ? api.unsavePlace(place.id) : api.savePlace(place.id))
    } catch (e) {
      setPlaces(wasSaved ? withPlace : without)
      toast(errorMessage(e, tr('Kaydedilenler güncellenemedi', 'Couldn’t update your saved places')))
    }
  }, [places, toast])

  const value = useMemo(() => ({ places, isSaved, toggle }), [places, isSaved, toggle])
  return <SavedPlacesContext.Provider value={value}>{children}</SavedPlacesContext.Provider>
}

export function useSavedPlaces(): SavedPlacesState {
  const context = useContext(SavedPlacesContext)
  if (!context) throw new Error('useSavedPlaces must be used inside SavedPlacesProvider')
  return context
}
