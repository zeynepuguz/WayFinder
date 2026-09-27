import { useCallback, useEffect, useState, type DependencyList } from 'react'
import { tr } from './i18n'

// Loads data for a page and tracks loading / error, with a reload function
export function useAsync<T>(load: () => Promise<T>, deps: DependencyList) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

  // The caller's deps decide when to load again (like useEffect)
  const run = useCallback(load, deps)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setData(await run())
    } catch (e) {
      setError(e instanceof Error ? e.message : tr('Bir hata oluştu', 'Something went wrong'))
    } finally {
      setLoading(false)
    }
  }, [run])

  useEffect(() => {
    void reload()
  }, [reload])

  return { data, setData, error, loading, reload }
}
