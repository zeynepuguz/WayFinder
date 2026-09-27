import { http } from './client'
import type {
  AccessPlan, AccessStatus, PlansResponse,
  AssistantReply, AuthResponse, ChatMessage, HomeResponse, Page, Place, PlaceCategory, Preferences,
  Recommendation, ReplanRequest, ReplanResponse, Route, RoutePlanRequest, RouteSummary, StopStatus, StopType, User,
} from './types'

// One function per backend endpoint, so pages never build URLs themselves
export const api = {
  register: (email: string, password: string, displayName: string) =>
    http.post<AuthResponse>('/auth/register', { email, password, displayName }),
  login: (email: string, password: string) => http.post<AuthResponse>('/auth/login', { email, password }),
  forgotPassword: (email: string) => http.post<void>('/auth/password/forgot', { email }),
  resetPassword: (email: string, code: string, newPassword: string) =>
    http.post<AuthResponse>('/auth/password/reset', { email, code, newPassword }),
  me: () => http.get<User>('/users/me'),
  deleteAccount: () => http.delete<void>('/users/me'),

  plans: () => http.get<PlansResponse>('/billing/plans'),
  accessStatus: () => http.get<AccessStatus>('/billing/me'),
  verifyGooglePlay: (productId: string, purchaseToken: string) =>
    http.post<AccessStatus>('/billing/google-play/verify', { productId, purchaseToken }),
  devPurchase: (plan: AccessPlan) => http.post<AccessStatus>('/billing/dev/purchase', { plan }),
  updatePreferences: (preferences: Preferences) => http.put<Preferences>('/users/me/preferences', preferences),

  home: (lat: number, lon: number) => http.get<HomeResponse>('/home', { lat, lon }),

  searchPlaces: (filter: {
    category?: PlaceCategory; q?: string; maxCost?: number; indoor?: boolean; page?: number; size?: number
  }) => http.get<Page<Place>>('/places', filter),
  nearbyPlaces: (lat: number, lon: number, radius = 1500) =>
    http.get<Place[]>('/places/nearby', { lat, lon, radius, limit: 50 }),
  place: (id: number) => http.get<Place>(`/places/${id}`),
  recommendations: (lat: number, lon: number, type?: StopType) =>
    http.get<Recommendation[]>('/recommendations', { lat, lon, type, limit: 5 }),

  routes: (saved = false) => http.get<RouteSummary[]>('/routes', { saved }),
  route: (id: number) => http.get<Route>(`/routes/${id}`),
  planRoute: (request: RoutePlanRequest) => http.post<Route>('/routes', request),
  updateRoute: (id: number, changes: { saved?: boolean; title?: string; status?: string }) =>
    http.patch<Route>(`/routes/${id}`, changes),
  deleteRoute: (id: number) => http.delete<void>(`/routes/${id}`),
  updateStop: (routeId: number, stopId: number, status: StopStatus) =>
    http.patch<Route>(`/routes/${routeId}/stops/${stopId}`, { status }),
  replan: (routeId: number, request: ReplanRequest) => http.post<ReplanResponse>(`/routes/${routeId}/replan`, request),

  savedPlaces: () => http.get<Place[]>('/saved/places'),
  savePlace: (id: number) => http.put<void>(`/saved/places/${id}`),
  unsavePlace: (id: number) => http.delete<void>(`/saved/places/${id}`),

  sendMessage: (message: string, latitude: number, longitude: number, routeId?: number) =>
    http.post<AssistantReply>('/assistant/messages', { message, latitude, longitude, routeId }),
  messages: () => http.get<ChatMessage[]>('/assistant/messages', { limit: 50 }),
}
