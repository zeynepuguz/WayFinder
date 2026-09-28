import { clampBox, inBox, isInTurkey, latestRequest, MAX_BOX_SPAN, placesQueryBox } from './geo'

describe('geo helpers', () => {
  it('knows the Türkiye service area', () => {
    expect(isInTurkey(40.991, 29.023)).toBe(true) // Kadıköy
    expect(isInTurkey(39.92, 32.85)).toBe(true) // Ankara
    expect(isInTurkey(38.42, 27.14)).toBe(true) // İzmir
    expect(isInTurkey(39.92, 44.04)).toBe(true) // Iğdır
    expect(isInTurkey(36.2, 36.16)).toBe(true) // Antakya
    expect(isInTurkey(48.85, 2.35)).toBe(false) // Paris
    expect(isInTurkey(42.7, 23.32)).toBe(false) // Sofia
    expect(isInTurkey(35.5, 33.9)).toBe(false) // south of the box
  })

  it('checks a point against a box', () => {
    const box = { south: 39.5, west: 32.3, north: 40.3, east: 33.3 }
    expect(inBox(box, 39.92, 32.85)).toBe(true)
    expect(inBox(box, 41, 29)).toBe(false)
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
