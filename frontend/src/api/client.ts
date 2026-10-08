import { currentLang, tr } from '../lib/i18n'
import type { ApiError, AuthResponse, User } from './types'

// Web (dev): same origin through the Vite proxy. Android app: the public API URL (VITE_API_BASE_URL).
const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
const TOKEN_KEY = 'nomi.token'
const REFRESH_KEY = 'nomi.refresh'
export const PAYMENT_REQUIRED_EVENT = 'nomi:payment-required'

export class ApiRequestError extends Error {
  status: number
  errors: Record<string, string>

  constructor(error: ApiError) {
    super(error.message)
    this.status = error.status
    this.errors = error.errors ?? {}
  }
}

function read(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function write(key: string, value: string | null) {
  try {
    if (value) localStorage.setItem(key, value)
    else localStorage.removeItem(key)
  } catch {
    // private mode etc.: the session just will not survive a reload
  }
}

/**
 * The short access token (15 min) for API calls and the refresh token that renews it. The session lasts 7 days
 * after the last use: every renewal moves its end (backend SessionService).
 */
export const tokenStore = {
  get: () => read(TOKEN_KEY),
  getRefresh: () => read(REFRESH_KEY),
  // null signs out: both tokens go
  set(token: string | null, refreshToken?: string | null) {
    write(TOKEN_KEY, token)
    if (token === null) write(REFRESH_KEY, null)
    else if (refreshToken !== undefined) write(REFRESH_KEY, refreshToken)
  },
}

// Called when the backend says the session is over (set by AuthProvider)
let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

// Called with the fresh profile after a renewal (set by AuthProvider)
let onRenewed: (user: User) => void = () => {}
export function setRenewedHandler(handler: (user: User) => void) {
  onRenewed = handler
}

type Renewal = 'renewed' | 'ended' | 'offline'
let renewing: Promise<Renewal> | null = null

/**
 * Renews the session with the refresh token; parallel callers share one renewal (the backend replaces the refresh
 * token, a second renewal with the old one would be refused).
 */
export function renewSession(): Promise<Renewal> {
  renewing ??= doRenew().finally(() => { renewing = null })
  return renewing
}

async function doRenew(): Promise<Renewal> {
  const used = tokenStore.getRefresh()
  if (!used) return 'ended'
  let response: Response
  try {
    response = await fetch(`${BASE}/auth/refresh`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: used }),
    })
  } catch {
    return 'offline'
  }
  if (response.ok) {
    const body = await response.json() as AuthResponse
    tokenStore.set(body.accessToken, body.refreshToken)
    onRenewed(body.user)
    return 'renewed'
  }
  // Another tab renewed with the same token a moment earlier: its new tokens are in the shared storage
  if (response.status === 401 && tokenStore.getRefresh() !== used) return 'renewed'
  return response.status === 401 ? 'ended' : 'offline'
}

/**
 * Absolute address of a file the backend serves itself (user photos: "/media/photos/…").
 * Web: same origin (Vite dev proxy / the server forwards /media). App: the host of VITE_API_BASE_URL.
 * Anything that is not a backend path or an http(s) URL gives null, so it is never rendered.
 */
export function mediaUrl(path: string | null | undefined, base: string = BASE): string | null {
  if (!path) return null
  if (/^https?:\/\//i.test(path)) return path
  if (!path.startsWith('/') || path.startsWith('//')) return null
  if (/^https?:\/\//i.test(base)) return new URL(base).origin + path
  return path
}

type Query = Record<string, string | number | boolean | undefined | null>

export function buildQuery(query?: Query): string {
  if (!query) return ''
  const params = new URLSearchParams()
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value))
  })
  const text = params.toString()
  return text ? `?${text}` : ''
}

async function request<T>(method: string, path: string, body?: unknown, query?: Query, signal?: AbortSignal,
                          retried = false): Promise<T> {
  // Accept-Language: the backend answers (assistant, route notes, place texts) in the app's language
  const headers: Record<string, string> = { Accept: 'application/json', 'Accept-Language': currentLang() }
  const token = tokenStore.get()
  if (token) headers.Authorization = `Bearer ${token}`
  // FormData (photo upload): the browser sets multipart/form-data with its boundary itself
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(BASE + path + buildQuery(query), {
      method,
      headers,
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
      signal,
    })
  } catch (e) {
    // Cancelled by the caller (e.g. the map moved again): not a connection problem
    if (signal?.aborted) throw e
    throw new ApiRequestError({ status: 0, message: tr('Sunucuya ulaşılamıyor', 'Can’t reach the server'), errors: {} })
  }

  // 402 = paid feature without an active pass: the app opens the paywall
  if (response.status === 402) {
    window.dispatchEvent(new CustomEvent(PAYMENT_REQUIRED_EVENT))
  }

  // Expired access token: renew the session once and repeat the request. Sign-in calls answer 401 for a wrong
  // password, which has nothing to renew
  if (response.status === 401 && !path.startsWith('/auth/') && (token || tokenStore.getRefresh())) {
    const renewal = retried ? 'ended' : await renewSession()
    if (renewal === 'renewed') return request<T>(method, path, body, query, signal, true)
    if (renewal === 'ended') onUnauthorized()
  }

  if (!response.ok) {
    // 502/503/504 come from the proxy (Vite / Caddy) while the backend is down or restarting, not from the backend
    const unreachable = response.status >= 502 && response.status <= 504
    let error: ApiError = {
      status: response.status,
      message: unreachable
        ? tr('Sunucuya şu an ulaşılamıyor, birazdan tekrar dene', 'Can’t reach the server right now, try again shortly')
        : response.statusText || tr('Bir hata oluştu', 'Something went wrong'),
      errors: {},
    }
    try {
      error = await response.json()
    } catch {
      // not JSON (e.g. proxy error page)
    }
    throw new ApiRequestError(error)
  }

  // 202/204 and other bodiless responses
  const text = await response.text()
  if (!text) return undefined as T
  return JSON.parse(text) as T
}

export const http = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) => request<T>('GET', path, undefined, query, signal),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
}
