import { renderHook } from '@testing-library/react'
import type { City, District } from '../api/types'
import { reloadHomeArea, setHomeArea, useHomeArea } from './homeArea'

const ADIYAMAN: City = { slug: 'adiyaman', name: 'Adıyaman', placeCount: 900, districtCount: 9, latitude: 37.76, longitude: 38.27,
  south: 0, west: 0, north: 0, east: 0 }
const MERKEZ: District = { slug: 'adiyaman-merkez', name: 'Adıyaman Merkez', placeCount: 500, latitude: 37.76, longitude: 38.27,
  south: 0, west: 0, north: 0, east: 0 }

describe('Ana Sayfa place', () => {
  afterEach(() => setHomeArea(null))

  it('the last place picked is still there after a reload', () => {
    setHomeArea({ city: ADIYAMAN, district: MERKEZ })
    reloadHomeArea()
    expect(renderHook(() => useHomeArea()).result.current).toEqual({ city: ADIYAMAN, district: MERKEZ })
  })

  it('“Konumumu kullan” is kept too', () => {
    setHomeArea({ city: ADIYAMAN, district: null })
    setHomeArea(null)
    reloadHomeArea()
    expect(renderHook(() => useHomeArea()).result.current).toBeNull()
  })

  it('something unreadable on the device is the device location', () => {
    localStorage.setItem('nomi.homeArea', '{not json')
    reloadHomeArea()
    expect(renderHook(() => useHomeArea()).result.current).toBeNull()
  })
})
