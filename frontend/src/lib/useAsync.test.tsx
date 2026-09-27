import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { useAsync } from './useAsync'

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
