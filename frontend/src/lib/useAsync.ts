import { useCallback, useEffect, useRef, useState, type DependencyList } from 'react'
import { errorMessage } from './format'
import { tr } from './i18n'

// Loads data for a page and tracks loading / error, with a reload function
export function useAsync<T>(load: () => Promise<T>, deps: DependencyList) {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  // Only the latest call may update state: a slow answer for old deps must not overwrite a newer one
  const latestCall = useRef(0)

  // The caller's deps decide when to load again (like useEffect)
  const run = useCallback(load, deps)

  const reload = useCallback(async () => {
    const call = ++latestCall.current
    setLoading(true)
    setError(null)
    try {
      const result = await run()
      if (call === latestCall.current) setData(result)
    } catch (e) {
      if (call === latestCall.current) setError(errorMessage(e, tr('Bir hata oluştu', 'Something went wrong')))
    } finally {
      if (call === latestCall.current) setLoading(false)
    }
  }, [run])

  useEffect(() => {
    void reload()
  }, [reload])

  return { data, setData, error, loading, reload }
}
