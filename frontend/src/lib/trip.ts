import { LocalNotifications } from '@capacitor/local-notifications'
import type { Route, RouteStop } from '../api/types'
import { isNativeApp } from './billing'
import { formatTime, todayIso } from './format'
import type { Translate } from './i18n'

// During a trip: how late the user is, and phone reminders before each stop (Android app only)

// Behind by less than this is normal; more than 3 hours means the plan is simply not being followed
const LATE_MIN = 15
const LATE_MAX = 180
// The reminder comes this long before the user should start walking to the next stop
const REMINDER_LEAD = 10
const ENABLED_KEY = 'nomi.tripReminders'

function minutesOf(time: string): number {
  return Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5))
}

function minutesNow(now: Date): number {
  return now.getHours() * 60 + now.getMinutes()
}

function isLive(route: Route, now: Date): boolean {
  return route.date === todayIso(now) && route.status !== 'COMPLETED' && route.status !== 'EXPIRED'
}

export function nextStop(route: Route): RouteStop | undefined {
  return route.stops.find(s => s.status === 'PLANNED')
}

// Minutes past the next stop's start while it is not visited yet; 0 = on time (or not today's route)
export function minutesBehind(route: Route, now = new Date()): number {
  const next = nextStop(route)
  if (!next || !isLive(route, now)) return 0
  const behind = minutesNow(now) - minutesOf(next.plannedStart)
  return behind >= LATE_MIN && behind <= LATE_MAX ? behind : 0
}

export interface Reminder {
  id: number
  at: Date
  title: string
  body: string
}

// One reminder per stop still ahead: time to leave for it (start minus the walk minus a few minutes)
export function tripReminders(route: Route, t: Translate, now = new Date()): Reminder[] {
  if (!isLive(route, now)) return []
  return route.stops.flatMap(stop => {
    if (stop.status !== 'PLANNED') return []
    const at = new Date(now)
    at.setHours(0, minutesOf(stop.plannedStart) - stop.walkingMinutes - REMINDER_LEAD, 0, 0)
    if (at <= now) return []
    const walk = stop.walkingMinutes > 0 ? t(` · ${stop.walkingMinutes} dk yürüme`, ` · ${stop.walkingMinutes} min walk`) : ''
    return [{
      // Unique per route and stop position, inside the 32-bit range Android needs
      id: (route.id % 2_000_000) * 100 + stop.position,
      at,
      title: t(`Sıradaki durak: ${stop.place.name}`, `Next stop: ${stop.place.name}`),
      body: `${formatTime(stop.plannedStart)}${walk}`,
    }]
  })
}

export function remindersEnabled(): boolean {
  try {
    return localStorage.getItem(ENABLED_KEY) === '1'
  } catch {
    return false
  }
}

export function remindersAvailable(): boolean {
  return isNativeApp()
}

// Asks for permission; false = the user said no
export async function enableReminders(): Promise<boolean> {
  const { display } = await LocalNotifications.requestPermissions()
  const granted = display === 'granted'
  try {
    localStorage.setItem(ENABLED_KEY, granted ? '1' : '0')
  } catch {
    // reminders then stay off after a restart
  }
  return granted
}

export function disableReminders() {
  try {
    localStorage.setItem(ENABLED_KEY, '0')
  } catch {
    // nothing to remember
  }
}

export async function cancelReminders(routeId: number) {
  if (!remindersAvailable()) return
  const { notifications } = await LocalNotifications.getPending()
  const old = notifications.filter(n => n.extra?.routeId === routeId)
  if (old.length > 0) await LocalNotifications.cancel({ notifications: old.map(n => ({ id: n.id })) })
}

// Replaces this route's scheduled reminders with ones for its current times (nothing when reminders are off)
export async function syncReminders(route: Route, t: Translate) {
  if (!remindersAvailable()) return
  try {
    await cancelReminders(route.id)
    if (!remindersEnabled()) return
    const reminders = tripReminders(route, t)
    if (reminders.length === 0) return
    await LocalNotifications.schedule({
      notifications: reminders.map(r => ({
        id: r.id, title: r.title, body: r.body, schedule: { at: r.at, allowWhileIdle: true }, extra: { routeId: route.id },
      })),
    })
  } catch {
    // a reminder is a convenience: the route screen still shows the next stop
  }
}
