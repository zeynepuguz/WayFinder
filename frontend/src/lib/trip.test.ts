import { describe, expect, it } from 'vitest'
import type { Route, RouteStop, StopStatus } from '../api/types'
import { todayIso } from './format'
import { minutesBehind, tripReminders } from './trip'

const t = (tr: string) => tr
const NOW = new Date(2026, 9, 5, 13, 0)

function stop(position: number, start: string, status: StopStatus = 'PLANNED', walkingMinutes = 12): RouteStop {
  return {
    id: position, position, type: 'LUNCH', typeLabel: 'Öğle yemeği', plannedStart: start, plannedEnd: start,
    distanceFromPreviousMeters: 900, walkingMinutes, reasons: [], status,
    place: { id: position, name: `Durak ${position}`, category: 'RESTAURANT', address: null, neighborhood: null, latitude: 41, longitude: 29, estimatedCost: null, rating: null, indoor: true },
  } as RouteStop
}

function route(stops: RouteStop[], date = todayIso(NOW)): Route {
  return { id: 7, date, status: 'ACTIVE', stops } as Route
}

describe('minutesBehind', () => {
  it('counts the minutes past the next unvisited stop', () => {
    expect(minutesBehind(route([stop(0, '10:00', 'VISITED'), stop(1, '12:30:00')]), NOW)).toBe(30)
  })

  it('a few minutes late, another day or a finished route is not late', () => {
    expect(minutesBehind(route([stop(1, '12:50')]), NOW)).toBe(0)
    expect(minutesBehind(route([stop(1, '12:00')], '2026-10-04'), NOW)).toBe(0)
    expect(minutesBehind({ ...route([stop(1, '12:00')]), status: 'COMPLETED' }, NOW)).toBe(0)
  })
})

describe('tripReminders', () => {
  it('reminds before leaving for each stop still ahead', () => {
    const reminders = tripReminders(route([stop(0, '12:00'), stop(1, '15:00', 'PLANNED', 20), stop(2, '18:00', 'SKIPPED')]), t, NOW)

    expect(reminders).toHaveLength(1)
    expect(reminders[0].at.getHours() * 60 + reminders[0].at.getMinutes()).toBe(14 * 60 + 30)
    expect(reminders[0].title).toBe('Sıradaki durak: Durak 1')
    expect(reminders[0].body).toBe('15:00 · 20 dk yürüme')
    expect(reminders[0].id).toBe(701)
  })
})
