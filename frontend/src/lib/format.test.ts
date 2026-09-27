import { formatCost, formatDistance, formatTime, haversineMeters, httpUrl, withinBudget } from './format'

describe('format helpers', () => {
  it('formats times, distances and costs for Turkish users', () => {
    expect(formatTime('09:30:00')).toBe('09:30')
    expect(formatDistance(320.4)).toBe('320 m')
    expect(formatDistance(1250)).toBe('1,3 km')
    expect(formatCost(0)).toBe('Ücretsiz')
    expect(formatCost(null)).toBe('Fiyat bilgisi yok')
    expect(formatCost(undefined)).toBe('Fiyat bilgisi yok')
    expect(formatCost(1500)).toBe('~1.500 TL')
  })

  it('never treats an unknown price as free in the budget filter', () => {
    expect(withinBudget(null, 200)).toBe(false)
    expect(withinBudget(undefined, 0)).toBe(false)
    expect(withinBudget(0, 0)).toBe(true)
    expect(withinBudget(150, 200)).toBe(true)
    expect(withinBudget(250, 200)).toBe(false)
  })

  it('computes distances between coordinates', () => {
    // Kadıköy pier -> Moda coast is roughly 1.3 km
    const meters = haversineMeters(40.991, 29.023, 40.9798, 29.026)
    expect(meters).toBeGreaterThan(1100)
    expect(meters).toBeLessThan(1400)
  })
})

describe('httpUrl', () => {
  it('keeps only http(s) links', () => {
    expect(httpUrl('https://upload.wikimedia.org/a.jpg')).toBe('https://upload.wikimedia.org/a.jpg')
    expect(httpUrl('javascript:alert(1)')).toBeNull()
    expect(httpUrl(null)).toBeNull()
    expect(httpUrl(undefined)).toBeNull()
  })
})
