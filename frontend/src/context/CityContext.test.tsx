import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import type { City } from '../api/types'
import { CityProvider, useCity } from './CityContext'

const city = (slug: string, name: string, placeCount = 100): City => ({
  slug, name, placeCount, districtCount: 10, latitude: 40, longitude: 30, south: 39, west: 29, north: 41, east: 31,
})
const CITIES = [city('ankara', 'Ankara'), city('istanbul', 'İstanbul'), city('izmir', 'İzmir'), city('van', 'Van', 0)]

const cities = vi.fn()
const cityAt = vi.fn()
vi.mock('../api', () => ({
  api: {
    cities: () => cities(),
    cityAt: (...args: unknown[]) => cityAt(...args),
  },
}))
let location = { latitude: 39.92, longitude: 32.85, source: 'gps' }
vi.mock('./LocationContext', () => ({ useUserLocation: () => location }))

const wrapper = ({ children }: { children: ReactNode }) => <CityProvider>{children}</CityProvider>

beforeEach(() => {
  localStorage.removeItem('nomi.city')
  cities.mockReset().mockResolvedValue(CITIES)
  cityAt.mockReset()
  location = { latitude: 39.92, longitude: 32.85, source: 'gps' }
})

describe('CityProvider', () => {
  it('uses the city at the GPS position', async () => {
    cityAt.mockResolvedValue(city('ankara', 'Ankara'))
    const { result } = renderHook(() => useCity(), { wrapper })
    await waitFor(() => expect(result.current.city?.name).toBe('Ankara'))
    expect(cityAt).toHaveBeenCalledWith(39.92, 32.85)
    expect(result.current.cities).toHaveLength(4)
  })

  it('prefers the saved choice and remembers a new one', async () => {
    localStorage.setItem('nomi.city', 'izmir')
    const { result } = renderHook(() => useCity(), { wrapper })
    await waitFor(() => expect(result.current.city?.slug).toBe('izmir'))
    expect(cityAt).not.toHaveBeenCalled()

    act(() => result.current.setCity('ankara'))
    await waitFor(() => expect(result.current.city?.slug).toBe('ankara'))
    expect(localStorage.getItem('nomi.city')).toBe('ankara')
  })

  it('falls back to Istanbul outside the cities, for demo locations and for cities without places', async () => {
    cityAt.mockRejectedValue(new Error('Not found'))
    const outside = renderHook(() => useCity(), { wrapper })
    await waitFor(() => expect(outside.result.current.city?.slug).toBe('istanbul'))
    outside.unmount()

    cityAt.mockReset().mockResolvedValue(city('van', 'Van', 0))
    const empty = renderHook(() => useCity(), { wrapper })
    await waitFor(() => expect(empty.result.current.city?.slug).toBe('istanbul'))
    empty.unmount()

    cityAt.mockReset()
    location = { latitude: 40.991, longitude: 29.023, source: 'demo-denied' }
    const demo = renderHook(() => useCity(), { wrapper })
    await waitFor(() => expect(demo.result.current.city?.slug).toBe('istanbul'))
    expect(cityAt).not.toHaveBeenCalled()
  })

  it('ignores a saved city that is unknown', async () => {
    localStorage.setItem('nomi.city', 'atlantis')
    const { result } = renderHook(() => useCity(), { wrapper })
    await waitFor(() => expect(result.current.city?.slug).toBe('istanbul'))
  })
})
