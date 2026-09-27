import { clampBox, isInIstanbul, latestRequest, MAX_BOX_SPAN, placesQueryBox } from './geo'

describe('geo helpers', () => {
  it('knows the Istanbul service area', () => {
    expect(isInIstanbul(40.991, 29.023)).toBe(true) // Kadıköy
    expect(isInIstanbul(41.0082, 28.9784)).toBe(true) // Sultanahmet
    expect(isInIstanbul(41.17, 29.61)).toBe(true) // Şile
    expect(isInIstanbul(39.92, 32.85)).toBe(false) // Ankara
    expect(isInIstanbul(40.77, 29.92)).toBe(false) // İzmit
  })

  it('keeps a small box as it is', () => {
    const box = { south: 40.98, west: 29.01, north: 41.0, east: 29.04 }
    expect(clampBox(box)).toEqual(box)
  })

  it('shrinks a large box around its center', () => {
    const box = clampBox({ south: 40.5, west: 28.0, north: 41.5, east: 29.0 })
    expect(box.north - box.south).toBeLessThanOrEqual(0.6)
    expect(box.east - box.west).toBeLessThanOrEqual(0.6)
    expect(box.north - box.south).toBeCloseTo(MAX_BOX_SPAN, 5)
    expect((box.north + box.south) / 2).toBeCloseTo(41.0, 5)
    expect((box.east + box.west) / 2).toBeCloseTo(28.5, 5)
  })

  it('only loads places when zoomed in enough', () => {
    const box = { south: 40.98, west: 29.01, north: 41.0, east: 29.04 }
    expect(placesQueryBox(12, box)).toBeNull()
    expect(placesQueryBox(13, box)).toEqual(box)
  })

  it('ignores answers to outdated requests and aborts them', () => {
    const requests = latestRequest()
    const first = requests.start()
    const second = requests.start()
    expect(first.signal.aborted).toBe(true)
    expect(requests.isLatest(first.id)).toBe(false)
    expect(requests.isLatest(second.id)).toBe(true)
    requests.cancel()
    expect(second.signal.aborted).toBe(true)
    expect(requests.isLatest(second.id)).toBe(false)
  })
})
