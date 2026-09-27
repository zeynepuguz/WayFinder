import { formatCost, formatDistance, formatTime, haversineMeters } from './format'

describe('format helpers', () => {
  it('formats times, distances and costs for Turkish users', () => {
    expect(formatTime('09:30:00')).toBe('09:30')
    expect(formatDistance(320.4)).toBe('320 m')
    expect(formatDistance(1250)).toBe('1,3 km')
    expect(formatCost(0)).toBe('Ücretsiz')
    expect(formatCost(null)).toBe('Ücretsiz')
    expect(formatCost(1500)).toBe('~1.500 TL')
  })

  it('computes distances between coordinates', () => {
    // Kadıköy pier -> Moda coast is roughly 1.3 km
    const meters = haversineMeters(40.991, 29.023, 40.9798, 29.026)
    expect(meters).toBeGreaterThan(1100)
    expect(meters).toBeLessThan(1400)
  })
})
