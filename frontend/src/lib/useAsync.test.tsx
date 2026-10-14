import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { clearAsyncCache, useAsync } from './useAsync'

describe('useAsync', () => {
  it('ignores a slow answer for old deps', async () => {
    const resolvers: Record<string, (value: string) => void> = {}
    const { result, rerender } = renderHook(({ key }) =>
      useAsync(() => new Promise<string>(resolve => { resolvers[key] = resolve }), [key]), { initialProps: { key: 'old' } })

    rerender({ key: 'new' })
    await act(async () => { resolvers.new('new data') })
    await act(async () => { resolvers.old('old data') })

    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toBe('new data')
  })
})

describe('useAsync cache (going back)', () => {
  it('shows a screen seen before at once and refreshes it quietly', async () => {
    clearAsyncCache()
    const first = renderHook(() => useAsync(() => Promise.resolve('route v1'), [], { cacheKey: 'route:7' }))
    await waitFor(() => expect(first.result.current.data).toBe('route v1'))
    first.unmount()

    let answer: (value: string) => void = () => {}
    const back = renderHook(() => useAsync(() => new Promise<string>(resolve => { answer = resolve }), [],
      { cacheKey: 'route:7' }))

    // No skeleton: the old data right away, while the new one is on its way
    expect(back.result.current.data).toBe('route v1')
    expect(back.result.current.loading).toBe(false)
    await act(async () => { answer('route v2') })
    expect(back.result.current.data).toBe('route v2')
  })

  it('a failed quiet refresh keeps what is on screen', async () => {
    clearAsyncCache()
    const first = renderHook(() => useAsync(() => Promise.resolve('places'), [], { cacheKey: 'saved' }))
    await waitFor(() => expect(first.result.current.data).toBe('places'))
    first.unmount()

    const back = renderHook(() => useAsync(() => Promise.reject(new Error('offline')), [], { cacheKey: 'saved' }))
    await waitFor(() => expect(back.result.current.loading).toBe(false))
    expect(back.result.current.data).toBe('places')
    expect(back.result.current.error).toBeNull()
  })

  it('nothing is kept after signing out', async () => {
    const first = renderHook(() => useAsync(() => Promise.resolve('my routes'), [], { cacheKey: 'routes:all' }))
    await waitFor(() => expect(first.result.current.data).toBe('my routes'))
    first.unmount()
    clearAsyncCache()

    const next = renderHook(() => useAsync(() => new Promise<string>(() => {}), [], { cacheKey: 'routes:all' }))
    expect(next.result.current.data).toBeNull()
    expect(next.result.current.loading).toBe(true)
  })
})
