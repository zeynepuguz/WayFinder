import { act, renderHook } from '@testing-library/react'
import { useLiveLocation } from './LocationContext'

type Success = (position: GeolocationPosition) => void
type Failure = (error: GeolocationPositionError) => void

function mockGeolocation() {
  const handlers: { success?: Success; failure?: Failure } = {}
  const geolocation = {
    watchPosition: vi.fn((success: Success, failure: Failure, _options?: PositionOptions) => {
      handlers.success = success
      handlers.failure = failure
      return 7
    }),
    clearWatch: vi.fn(),
    getCurrentPosition: vi.fn(),
  }
  Object.defineProperty(navigator, 'geolocation', { value: geolocation, configurable: true })
  return { geolocation, handlers }
}

const fix = (latitude: number, longitude: number, accuracy: number) =>
  ({ coords: { latitude, longitude, accuracy }, timestamp: Date.now() }) as unknown as GeolocationPosition

describe('useLiveLocation', () => {
  it('does nothing while disabled', () => {
    const { geolocation } = mockGeolocation()
    const { result } = renderHook(() => useLiveLocation(false))
    expect(geolocation.watchPosition).not.toHaveBeenCalled()
    expect(result.current.status).toBe('off')
  })

  it('follows the position with high accuracy and stops on unmount', () => {
    const { geolocation, handlers } = mockGeolocation()
    const { result, unmount } = renderHook(() => useLiveLocation(true))

    expect(result.current.status).toBe('locating')
    expect(geolocation.watchPosition.mock.calls[0][2]).toMatchObject({ enableHighAccuracy: true })

    act(() => handlers.success!(fix(41.0, 29.0, 12)))
    expect(result.current).toEqual({ latitude: 41.0, longitude: 29.0, accuracy: 12, status: 'live' })

    // a timeout keeps the last known position
    act(() => handlers.failure!({ code: 3, PERMISSION_DENIED: 1 } as GeolocationPositionError))
    expect(result.current.status).toBe('live')
    expect(result.current.latitude).toBe(41.0)

    unmount()
    expect(geolocation.clearWatch).toHaveBeenCalledWith(7)
  })

  it('reports a refused permission', () => {
    const { handlers } = mockGeolocation()
    const { result } = renderHook(() => useLiveLocation(true))
    act(() => handlers.failure!({ code: 1, PERMISSION_DENIED: 1 } as GeolocationPositionError))
    expect(result.current.status).toBe('denied')
  })
})
