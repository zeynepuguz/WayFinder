import type { ApiError } from './types'

// Web (dev): same origin through the Vite proxy. Android app: the public API URL (VITE_API_BASE_URL).
const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
const TOKEN_KEY = 'nomi.token'
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

export const tokenStore = {
  get(): string | null {
    try {
      return localStorage.getItem(TOKEN_KEY)
    } catch {
      return null
    }
  },
  set(token: string | null) {
    try {
      if (token) localStorage.setItem(TOKEN_KEY, token)
      else localStorage.removeItem(TOKEN_KEY)
    } catch {
      // private mode etc.: the session just will not survive a reload
    }
  },
}

// Called when the backend says the token is no longer valid (set by AuthProvider)
let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
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

async function request<T>(method: string, path: string, body?: unknown, query?: Query): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = tokenStore.get()
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(BASE + path + buildQuery(query), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiRequestError({ status: 0, message: 'Sunucuya ulaşılamıyor', errors: {} })
  }

  // 402 = paid feature without an active pass: the app opens the paywall
  if (response.status === 402) {
    window.dispatchEvent(new CustomEvent(PAYMENT_REQUIRED_EVENT))
  }

  if (response.status === 401 && token) {
    onUnauthorized()
  }

  if (!response.ok) {
    let error: ApiError = { status: response.status, message: response.statusText, errors: {} }
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
  get: <T>(path: string, query?: Query) => request<T>('GET', path, undefined, query),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body ?? {}),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  delete: <T>(path: string) => request<T>('DELETE', path),
}
