import { useSyncExternalStore } from 'react'
import type { Route } from '../api/types'

// A route the assistant planned that is not in "Rotalarım" yet: offered (add / delete) once the user leaves the chat
let current: Route | null = null
const listeners = new Set<() => void>()

export function offerRoute(route: Route | null) {
  current = route
  listeners.forEach(listener => listener())
}

export function useRouteOffer(): Route | null {
  return useSyncExternalStore(
    listener => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    () => current,
  )
}
