import { useCallback, useEffect, useRef, useState, type DependencyList, type SetStateAction } from 'react'
import { errorMessage } from './format'
import { tr } from './i18n'

// Data of screens the user already saw, by cache key: going back shows it at once (no skeleton) while it is loaded
// again in the background. Small, and cleared on sign-out (clearAsyncCache) so another user never sees it
const cache = new Map<string, unknown>()
const MAX_CACHED = 60

export function clearAsyncCache() {
  cache.clear()
}

function remember(key: string, value: unknown) {
  cache.delete(key)
  cache.set(key, value)
  if (cache.size > MAX_CACHED) cache.delete(cache.keys().next().value as string)
}

/**
 * Loads data for a page and tracks loading / error, with a reload function.
 *
 * @param options.cacheKey what this data is ("route:12"): set it on screens the user comes back to, so going back
 *                         continues where they were instead of loading everything again
 */
export function useAsync<T>(load: () => Promise<T>, deps: DependencyList, options?: { cacheKey?: string }) {
  const key = options?.cacheKey
  const [data, setState] = useState<T | null>(() => (key && cache.has(key) ? cache.get(key) as T : null))
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(() => !(key && cache.has(key)))
  // Only the latest call may update state: a slow answer for old deps must not overwrite a newer one
  const latestCall = useRef(0)
  const keyRef = useRef(key)
  keyRef.current = key

  // The caller's deps decide when to load again (like useEffect)
  const run = useCallback(load, deps)

  const reload = useCallback(async () => {
    const call = ++latestCall.current
    const cacheKey = keyRef.current
    const cached = cacheKey !== undefined && cache.has(cacheKey)
    // Seen before: show it right away and refresh quietly
    if (cached) {
      setState(cache.get(cacheKey) as T)
      setLoading(false)
    } else {
      setLoading(true)
    }
    setError(null)
    try {
      const result = await run()
      if (call === latestCall.current) {
        setState(result)
        if (cacheKey !== undefined) remember(cacheKey, result)
      }
    } catch (e) {
      // A failed background refresh keeps what is on screen
      if (call === latestCall.current && !cached) setError(errorMessage(e, tr('Bir hata oluştu', 'Something went wrong')))
    } finally {
      if (call === latestCall.current) setLoading(false)
    }
  }, [run])

  useEffect(() => {
    void reload()
  }, [reload])

  // Changes made on the page (a stop marked visited) are kept for the way back too
  const setData = useCallback((value: SetStateAction<T | null>) => {
    setState(current => {
      const next = typeof value === 'function' ? (value as (prev: T | null) => T | null)(current) : value
      if (keyRef.current !== undefined && next !== null) remember(keyRef.current, next)
      return next
    })
  }, [])

  return { data, setData, error, loading, reload }
}
