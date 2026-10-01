// Mirrors the Spring Boot DTOs (com.nomi.wayfinder.dto.*)

export type PlaceCategory =
  | 'BREAKFAST' | 'RESTAURANT' | 'CAFE' | 'DESSERT' | 'ATTRACTION' | 'MUSEUM' | 'PARK' | 'CULTURE'
  // Places to pray at and grocery shops: Explore only (never a route stop or a suggestion)
  | 'WORSHIP' | 'MARKET'
export type StopType = 'BREAKFAST' | 'SIGHTSEEING' | 'LUNCH' | 'COFFEE' | 'DESSERT' | 'DINNER'
export type WalkingTolerance = 'LOW' | 'MEDIUM' | 'HIGH'
// EXPIRED: the route's day is over and it was never finished (read-only, shown under "Geçmiş rotalar")
export type RouteStatus = 'DRAFT' | 'ACTIVE' | 'COMPLETED' | 'EXPIRED'
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
  // District (ilçe) name; null when the place lies outside the known district borders
  district: string | null
  // City (il) name; null when unknown
  city: string | null
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
  // the business' own phone / website (Overture Maps); null / missing = unknown
  phone?: string | null
  website?: string | null
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

// One of a city's districts (GET /districts?city=, sorted by name); the box frames it on the map
export interface District extends MapBox {
  slug: string
  name: string
  latitude: number
  longitude: number
  placeCount: number
}

// One of Türkiye's 81 cities (GET /cities, sorted); placeCount 0 = not imported yet ("coming soon").
// latitude/longitude is the city's label point (its centre on the map)
export interface City extends MapBox {
  slug: string
  name: string
  latitude: number
  longitude: number
  placeCount: number
  districtCount: number
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
  // false = no stop has a known price (show "no price info", not "~0 TL"); absent on older servers
  costKnown?: boolean
  // where the route starts ("Kadıköy merkezi"); null/absent for older routes
  startLabel?: string | null
}

export interface HomeResponse {
  weather: WeatherNow | null
  suggestedStopType: StopType
  suggestions: Recommendation[]
  // today's unfinished route ("Aktif rotan"); never a route of a past day
  currentRoute: RouteSummary | null
  // "Yarınki rotan": only when there is no route today
  tomorrowRoute?: RouteSummary | null
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
  // where the route starts ("Kadıköy merkezi"); null/absent for older routes
  startLabel?: string | null
  weather: { condition: string | null; temperature: number | null; advice: string | null }
  notes: string[]
  stops: RouteStop[]
  createdAt: string
  updatedAt: string
}

// LOCATION: start at latitude/longitude (the device); AREA: start in the chosen city / district (lat/lon not sent)
export type RouteStartMode = 'LOCATION' | 'AREA'

export interface RoutePlanRequest {
  latitude?: number
  longitude?: number
  startMode?: RouteStartMode
  // city / district slugs (AREA)
  city?: string
  district?: string
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

// Ready-made route of a city or district (GET /routes/popular): the area's most popular sights that lie
// close together, in walking order, with meals / coffee planned in between
export interface PopularRouteStop {
  // "HH:mm"
  time: string
  durationMinutes: number
  type: StopType
  typeLabel: string
  walkingMinutes: number
  distanceMeters: number
  place: {
    id: number
    name: string
    category: PlaceCategory
    latitude: number
    longitude: number
    image: PlaceImage | null
    estimatedCost: number | null
    verified: boolean
    district: string | null
    // Wikipedia-based popularity; null = unknown
    popularity: number | null
  }
}

export interface PopularRoute {
  // stable id of the route; POST /routes/popular/start takes it
  key: string
  // real area name(s): "Sultanahmet ve çevresi", "Galata–Karaköy"
  title: string
  description: string
  // why these places count as popular ("Wikipedia’da en çok okunan yerler")
  popularityNote: string
  // the first stop (the route starts there)
  startLabel: string
  startLatitude: number
  startLongitude: number
  date: string
  stops: PopularRouteStop[]
  totalWalkingMinutes: number
  // sum of the known prices per person; null when no stop has a known price
  estimatedCostPerPerson: number | null
  // stops whose price is unknown (not part of estimatedCostPerPerson)
  unknownPriceStops: number
}

export interface ReplanRequest {
  type: ReplanType
  latitude: number
  longitude: number
  stopId?: number
  stopType?: StopType
  interest?: string
  // REPLACE_STOP: what the new place should be like, in the user's words
  wish?: string
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
  // The chat the message went to (a new one when none was given)
  conversationId: number
}

export interface ChatMessage {
  id: number
  role: 'USER' | 'ASSISTANT'
  content: string
  routeId: number | null
  conversationId?: number
  createdAt: string
}

// One assistant chat ("Sohbetler"). routeId = the route this chat planned: only that route is changed by its messages
export interface ConversationSummary {
  id: number
  // null until the first message
  title: string | null
  routeId: number | null
  routeTitle: string | null
  lastMessageAt: string
  // the newest message, shortened
  preview: string | null
}

// ---------- user photos ("Kullanıcılarımızdan fotoğraflar") ----------

// An approved user photo of a place or district (GET …/photos: at most 10, newest first).
// url / thumbUrl are backend paths ("/media/photos/…"): render them through mediaUrl().
export interface UserPhoto {
  id: number
  url: string
  thumbUrl: string
  width: number
  height: number
  createdAt: string
  uploader: string
}

export type PhotoStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

// Where a photo is added: a place, or a district of a city (slugs)
export type PhotoTarget = { type: 'PLACE'; id: number } | { type: 'DISTRICT'; city: string; district: string }

// Device position at upload time, used by the backend only to check the photo was taken there
export interface DevicePosition {
  latitude: number
  longitude: number
  accuracy: number
}

// 202 answer: the photo waits for the automatic check
export interface PhotoUploadResult {
  id: number
  status: PhotoStatus
  // The location check runs at upload: a photo can come back REJECTED right away
  rejectReason?: string | null
  rejectMessage?: string | null
}

// One of my own uploads (GET /me/photos)
export interface MyPhoto {
  id: number
  url: string
  thumbUrl: string
  status: PhotoStatus
  rejectReason: string | null
  // Localized reason shown to the user when the photo was not published
  rejectMessage: string | null
  target: { type: 'PLACE' | 'DISTRICT'; id?: number; name: string; city?: string; district?: string }
  createdAt: string
}
