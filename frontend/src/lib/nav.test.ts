import { describe, expect, it } from 'vitest'
import { googleMapsPinUrl } from './format'
import { safeNext } from './nav'

describe('safeNext', () => {
  it('follows same-site paths only', () => {
    expect(safeNext('/routes/5?x=1')).toBe('/routes/5?x=1')
    expect(safeNext('//evil.example')).toBeNull()
    expect(safeNext(String.raw`/\evil.example`)).toBeNull()
    expect(safeNext('https://evil.example')).toBeNull()
    expect(safeNext(null)).toBeNull()
  })
})

describe('googleMapsPinUrl', () => {
  it('searches the city and the name around the pin, without repeating the city', () => {
    expect(googleMapsPinUrl({ name: 'Akdağ Çayevi', city: 'Amasya', latitude: 40.822, longitude: 35.655 }))
      .toBe('https://www.google.com/maps/search/Amasya%20Akda%C4%9F%20%C3%87ayevi/@40.822,35.655,17z')
    expect(googleMapsPinUrl({ name: 'Amasya Seyir Cafe', city: 'Amasya', latitude: 1, longitude: 2 }))
      .toBe('https://www.google.com/maps/search/Amasya%20Seyir%20Cafe/@1,2,17z')
  })
})
