// Mirrors the Spring Boot DTOs (com.nomi.wayfinder.dto.*)

export type PlaceCategory =
  | 'BREAKFAST' | 'RESTAURANT' | 'CAFE' | 'DESSERT' | 'ATTRACTION' | 'MUSEUM' | 'PARK' | 'CULTURE'
export type StopType = 'BREAKFAST' | 'SIGHTSEEING' | 'LUNCH' | 'COFFEE' | 'DESSERT' | 'DINNER'
export type WalkingTolerance = 'LOW' | 'MEDIUM' | 'HIGH'
export type RouteStatus = 'DRAFT' | 'ACTIVE' | 'COMPLETED'
export type StopStatus = 'PLANNED' | 'VISITED' | 'SKIPPED'
export type ReplanType =
  | 'TIRED' | 'WEATHER_CHANGED' | 'REMOVE_STOP' | 'REPLACE_STOP' | 'ADD_STOP' | 'ADD_INTEREST' | 'LESS_WALKING'

export interface ApiError {
  status: number
  message: string
  errors: Record<string, string>
}

// ---------- auth ----------

export interface Preferences {
  walkingTolerance: WalkingTolerance
  defaultPartySize: number
  defaultBudget: number | null
  interests: string[]
}

export interface User {
  id: number
  email: string
  displayName: string
  role: 'USER' | 'ADMIN'
  preferences: Preferences
  access: AccessStatus
}

// ---------- billing ----------

export type AccessPlan = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY'

export interface AccessStatus {
  active: boolean
  plan: AccessPlan | null
  expiresAt: string | null
  // unlimited access without paying (owner accounts listed in the backend .env)
  free: boolean
}

export interface Plan {
  plan: AccessPlan
  label: string
  productId: string
  priceTry: number
  days: number
}

export interface PlansResponse {
  plans: Plan[]
  devMode: boolean
}

export interface AuthResponse {
  accessToken: string
  tokenType: string
  expiresAt: string
  user: User
}

// ---------- places ----------

export interface OpeningHours {
  dayOfWeek: number
  opensAt: string
  closesAt: string
}

export interface Place {
  id: number
  name: string
  description: string | null
  address: string | null
  neighborhood: string | null
  latitude: number
  longitude: number
  category: PlaceCategory
  estimatedCost: number | null
  rating: number | null
  indoor: boolean
  avgVisitMinutes: number | null
  tags: string[]
  openingHours: OpeningHours[]
  // openingHours empty = hours unknown, then openNow is null
  openNow: boolean | null
  source: string
  lastVerifiedAt: string | null
  // false for places imported from OpenStreetMap (details may be out of date)
  verified: boolean
  // link to the OpenStreetMap object for imported places
  sourceUrl: string | null
  // one free-licensed photo when available (most cafes and restaurants have none)
  image: PlaceImage | null
  distanceMeters?: number
}

// Wikimedia Commons photo; url is an 800px thumbnail hot-linked from upload.wikimedia.org
export interface PlaceImage {
  url: string
  author: string | null
  license: string | null
  // the Commons file page, used for the credit link
  sourceUrl: string | null
}

// /places/nearby and /places/in-area items (distanceMeters is set when a position was sent)
export type NearbyPlace = Place

export interface MapBox {
  south: number
  west: number
  north: number
  east: number
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface Recommendation {
  place: Place
  type: StopType
  score: number
  // farther items: reasons[0] is a localized distance line ("1,8 km uzakta (yürüyerek ~23 dk)")
  reasons: string[]
  // null for nearby items; a short localized sentence for farther ones
  whyBetter: string | null
}

export interface TieredRecommendations {
  nearby: Recommendation[]
  farther: Recommendation[]
}

// ---------- weather / home ----------

export interface WeatherNow {
  temperature: number
  apparentTemperature: number
  condition: string
  conditionLabel: string
  advice: string
}

export interface RouteSummary {
  id: number
  title: string
  date: string
  status: RouteStatus
  saved: boolean
  stopCount: number
  totalEstimatedCost: number
  createdAt: string
}

export interface HomeResponse {
  weather: WeatherNow | null
  suggestedStopType: StopType
  suggestions: Recommendation[]
  currentRoute: RouteSummary | null
  prompts: string[]
}

// ---------- routes ----------

export interface StopPlace {
  id: number
  name: string
  category: PlaceCategory
  address: string | null
  neighborhood: string | null
  latitude: number
  longitude: number
  estimatedCost: number | null
  rating: number | null
  indoor: boolean
  // same photo as the place (optional so older route payloads still type-check)
  image?: PlaceImage | null
}

export interface RouteStop {
  id: number
  position: number
  type: StopType
  typeLabel: string
  plannedStart: string
  plannedEnd: string
  distanceFromPreviousMeters: number
  walkingMinutes: number
  reasons: string[]
  status: StopStatus
  place: StopPlace
}

export interface Route {
  id: number
  title: string
  date: string
  status: RouteStatus
  saved: boolean
  startLatitude: number
  startLongitude: number
  startTime: string
  endTime: string
  partySize: number
  budget: number | null
  totalEstimatedCost: number
  totalWalkingMeters: number
  totalWalkingMinutes: number
  walkingTolerance: WalkingTolerance
  interests: string[]
  weather: { condition: string | null; temperature: number | null; advice: string | null }
  notes: string[]
  stops: RouteStop[]
  createdAt: string
  updatedAt: string
}

export interface RoutePlanRequest {
  latitude: number
  longitude: number
  date?: string
  startTime?: string
  endTime?: string
  partySize?: number
  budget?: number
  walkingTolerance?: WalkingTolerance
  stops?: StopType[]
  interests?: string[]
  title?: string
}

export interface ReplanRequest {
  type: ReplanType
  latitude: number
  longitude: number
  stopId?: number
  stopType?: StopType
  interest?: string
}

export interface ReplanResponse {
  route: Route
  changes: string[]
}

// ---------- assistant ----------

export interface AssistantReply {
  reply: string
  intent: { type: string; source: string | null }
  route: Route | null
  recommendations: Recommendation[]
  // 0–2 better fits that are not close to the user, shown below the main picks (empty for other intents)
  fartherRecommendations?: Recommendation[]
  changes: string[]
}

export interface ChatMessage {
  id: number
  role: 'USER' | 'ASSISTANT'
  content: string
  routeId: number | null
  createdAt: string
}
