import { http } from './client'
import type {
  AccessPlan, AccessStatus, AiUsageSummary, PlansResponse, DevicePosition, MyPhoto, PhotoTarget, PhotoUploadResult, UserPhoto,
  AssistantReply, AuthResponse, ChatMessage, CodePurpose, CodeSent, OpenSession, RegisterRequest, City, ConversationSummary, District, HomeResponse, MapBox, NearbyPlace, Page, Place, PlaceAvailability, ReportReason, ReviewAction, ReviewCounts, ReviewedPlace, AppFeedback, PlaceCategory,
  PopularRoute, Preferences,
  Recommendation, ReplanRequest, ReplanResponse, Route, RoutePlanRequest, RouteSummary, StopStatus, StopType, User,
} from './types'

// Multipart body of a photo upload: the original file (EXIF intact) plus the device position when known
function photoForm(file: File, position?: DevicePosition | null): FormData {
  const form = new FormData()
  form.append('file', file)
  if (position) {
    form.append('latitude', String(position.latitude))
    form.append('longitude', String(position.longitude))
    form.append('accuracy', String(position.accuracy))
  }
  return form
}

const photosPath = (target: PhotoTarget) => (target.type === 'PLACE'
  ? `/places/${target.id}/photos`
  : `/cities/${encodeURIComponent(target.city)}/districts/${encodeURIComponent(target.district)}/photos`)

// One function per backend endpoint, so pages never build URLs themselves
export const api = {
  // Sign-up and sign-in: step one e-mails a 6-digit code, step two (verify) signs in with it
  register: (request: RegisterRequest) => http.post<CodeSent>('/auth/register', request),
  verifyRegister: (email: string, code: string) => http.post<AuthResponse>('/auth/register/verify', { email, code }),
  login: (email: string, password: string) => http.post<CodeSent>('/auth/login', { email, password }),
  verifyLogin: (email: string, code: string) => http.post<AuthResponse>('/auth/login/verify', { email, code }),
  resendCode: (email: string, purpose: CodePurpose) => http.post<void>('/auth/code/resend', { email, purpose }),
  logout: (refreshToken: string) => http.post<void>('/auth/logout', { refreshToken }),
  forgotPassword: (email: string) => http.post<void>('/auth/password/forgot', { email }),
  resetPassword: (email: string, code: string, newPassword: string) =>
    http.post<AuthResponse>('/auth/password/reset', { email, code, newPassword }),
  me: () => http.get<User>('/users/me'),
  // Devices signed in to the account (profile: "Açık oturumlar")
  sessions: () => http.get<OpenSession[]>('/users/me/sessions'),
  endSession: (id: number) => http.delete<void>(`/users/me/sessions/${id}`),
  endOtherSessions: () => http.post<{ ended: number }>('/users/me/sessions/end-others'),
  deleteAccount: () => http.delete<void>('/users/me'),

  plans: () => http.get<PlansResponse>('/billing/plans'),
  verifyGooglePlay: (productId: string, purchaseToken: string) =>
    http.post<AccessStatus>('/billing/google-play/verify', { productId, purchaseToken }),
  devPurchase: (plan: AccessPlan) => http.post<AccessStatus>('/billing/dev/purchase', { plan }),
  updatePreferences: (preferences: Preferences) => http.put<Preferences>('/users/me/preferences', preferences),

  home: (lat: number, lon: number) => http.get<HomeResponse>('/home', { lat, lon }),

  searchPlaces: (filter: {
    category?: PlaceCategory; city?: string; district?: string; q?: string; maxCost?: number; indoor?: boolean; page?: number; size?: number
    // a sub-kind (İbadet > mosque / church / synagogue / cemevi)
    tag?: string
  }) => http.get<Page<Place>>('/places', filter),
  nearbyPlaces: (lat: number, lon: number, radius = 1500, limit = 50, category?: PlaceCategory, tag?: string) =>
    http.get<NearbyPlace[]>('/places/nearby', { lat, lon, radius, limit, category, tag }),
  // Places inside the visible map box (max 0.6° per side, max 300 items); distance from lat/lon when given
  placesInArea: (box: MapBox, options: { lat?: number; lon?: number; category?: PlaceCategory; tag?: string; limit?: number } = {},
    signal?: AbortSignal) =>
    http.get<NearbyPlace[]>('/places/in-area', {
      ...box, lat: options.lat, lon: options.lon, category: options.category, tag: options.tag, limit: options.limit ?? 300,
    }, signal),
  cities: () => http.get<City[]>('/cities'),
  // The city at a position; 404 when it is not inside one of Türkiye's cities
  cityAt: (lat: number, lon: number) => http.get<City>('/cities/at', { lat, lon }),
  districts: (city: string) => http.get<District[]>('/districts', { city }),
  place: (id: number) => http.get<Place>(`/places/${id}`),
  placeAvailability: (id: number) => http.get<PlaceAvailability>(`/places/${id}/availability`),
  reportPlace: (id: number, reason: ReportReason) => http.post<void>(`/places/${id}/reports`, { reason }),
  sendFeedback: (message: string) => http.post<void>('/feedback', { message }),
  // The owner's admin area
  reviewCounts: () => http.get<ReviewCounts>('/admin/review/counts'),
  reviewPlace: (id: number, action: ReviewAction) => http.post<void>(`/admin/review/places/${id}`, { action }),
  removedPlaces: (city?: string) => http.get<ReviewedPlace[]>(`/admin/review/removed${city ? `?city=${encodeURIComponent(city)}` : ''}`),
  suspectPlaces: (city?: string) => http.get<ReviewedPlace[]>(`/admin/review/suspects${city ? `?city=${encodeURIComponent(city)}` : ''}`),
  placeReports: () => http.get<ReviewedPlace[]>('/admin/review/reports'),
  dismissReports: (placeId: number) => http.post<void>(`/admin/review/reports/${placeId}/dismiss`),
  feedbackList: () => http.get<AppFeedback[]>('/admin/review/feedback'),
  markFeedbackRead: (id: number) => http.post<void>(`/admin/review/feedback/${id}/read`),
  aiUsage: () => http.get<AiUsageSummary>('/admin/ai-usage'),
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
  // Popular routes of an area (public); starting one creates a normal route (account + pass, 402 otherwise)
  popularRoutes: (city: string, district?: string) => http.get<PopularRoute[]>('/routes/popular', { city, district }),
  // key: PopularRoute.key of the list shown for this city / district and day (date: null = today)
  startPopularRoute: (request: { city: string; district?: string; key: string; date?: string }) =>
    http.post<Route>('/routes/popular/start', request),
  replan: (routeId: number, request: ReplanRequest) => http.post<ReplanResponse>(`/routes/${routeId}/replan`, request),
  // Group plans: sharing is the owner's; joining, voting and leaving need no pass
  shareRoute: (routeId: number) => http.post<{ token: string }>(`/routes/${routeId}/share`),
  unshareRoute: (routeId: number) => http.delete<void>(`/routes/${routeId}/share`),
  joinRoute: (code: string) => http.post<{ routeId: number }>(`/shared-routes/${encodeURIComponent(code)}/join`),
  leaveRoute: (routeId: number) => http.delete<void>(`/shared-routes/${routeId}/membership`),
  voteStop: (routeId: number, placeId: number, vote: -1 | 0 | 1) => http.post<Route>(`/shared-routes/${routeId}/votes`, { placeId, vote }),

  savedPlaces: () => http.get<Place[]>('/saved/places'),
  savePlace: (id: number) => http.put<void>(`/saved/places/${id}`),
  unsavePlace: (id: number) => http.delete<void>(`/saved/places/${id}`),

  // User photos: approved ones are public; adding, listing my own and deleting need an account
  photos: (target: PhotoTarget) => http.get<UserPhoto[]>(photosPath(target)),
  uploadPhoto: (target: PhotoTarget, file: File, position?: DevicePosition | null) =>
    http.post<PhotoUploadResult>(photosPath(target), photoForm(file, position)),
  myPhotos: () => http.get<MyPhoto[]>('/me/photos'),
  deletePhoto: (id: number) => http.delete<void>(`/photos/${id}`),

  // Without conversationId the backend starts a new chat; the reply carries its id
  sendMessage: (message: string, latitude: number, longitude: number, conversationId?: number | null) =>
    http.post<AssistantReply>('/assistant/messages', { message, latitude, longitude, conversationId: conversationId ?? undefined }),
  conversations: () => http.get<ConversationSummary[]>('/assistant/conversations'),
  conversationMessages: (id: number) => http.get<ChatMessage[]>(`/assistant/conversations/${id}/messages`),
  renameConversation: (id: number, title: string) => http.patch<ConversationSummary>(`/assistant/conversations/${id}`, { title }),
  deleteConversation: (id: number) => http.delete<void>(`/assistant/conversations/${id}`),
}
