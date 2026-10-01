import 'leaflet/dist/leaflet.css'
import L from 'leaflet'
import { ChevronDown, ChevronUp, LocateFixed, MapPin, Sparkles, X, ZoomIn } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { AttributionControl, Circle, MapContainer, Marker, TileLayer } from 'react-leaflet'
import { Link } from 'react-router'
import { api } from '../api'
import type { City, District, NearbyPlace, Place, PlaceCategory, Recommendation } from '../api/types'
import { locationLabel, useLiveLocation, useUserLocation } from '../context/LocationContext'
import { CATEGORY_LABELS, formatCost, formatDistance, haversineMeters } from '../lib/format'
import { CATEGORY_PIN, inBox, isInTurkey, latestRequest, MAX_MAP_PINS, MIN_PLACES_ZOOM, placesQueryBox } from '../lib/geo'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'
import { TILE_ATTRIBUTION, TILE_URL } from './mapTiles'
import { OpenBadge, VerifiedBadge } from './PlaceViews'
import { Skeleton, Spinner } from './ui'
import { CategoryTile } from './visuals'

// Accuracy circles bigger than this say more about the phone than about where the user is
const MAX_ACCURACY_CIRCLE = 300
// Moving more than this (live GPS) asks for new recommendations
const RECOMMENDATION_REFRESH_METERS = 400
const FETCH_DEBOUNCE_MS = 300

// Pins are drawn with CSS; icons are cached because Leaflet re-creates the DOM on every icon change
const iconCache = new Map<string, L.DivIcon>()
function placeIcon(category: PlaceCategory, verified: boolean, selected: boolean): L.DivIcon {
  const key = `${category}-${verified}-${selected}`
  let icon = iconCache.get(key)
  if (!icon) {
    const { color, symbol } = CATEGORY_PIN[category]
    const classes = ['map-pin', 'map-pin-cat', verified ? '' : 'map-pin-osm',
      selected ? 'map-pin-selected' : ''].filter(Boolean).join(' ')
    icon = L.divIcon({
      className: '',
      html: `<div class="${classes}" style="background:${color}"><span>${symbol}</span></div>`,
      iconSize: [30, 30],
      iconAnchor: [15, 30],
    })
    iconCache.set(key, icon)
  }
  return icon
}

const liveIcon = L.divIcon({ className: '', html: '<div class="live-dot"></div>', iconSize: [18, 18], iconAnchor: [9, 9] })

/**
 * Full-height "around me" map: live position, places in the visible area and recommendations.
 * Places load for the map bounds after each move (debounced, stale answers ignored) when zoomed in enough.
 */
export function LiveMap({ category, tag = null, focusArea = null, focusCity = null }: {
  category: PlaceCategory | null
  // A sub-kind (İbadet > mosque / church / ...); null = all of the category
  tag?: string | null
  // A chosen district: the map moves there (and stops following the user)
  focusArea?: District | null
  // The chosen city (no district): the map flies to its centre when it changes,
  // or on opening when the user is not in that city
  focusCity?: City | null
}) {
  const t = useT()
  const fallback = useUserLocation()
  const live = useLiveLocation(true)

  // The live fix counts only inside the service area; otherwise the app-wide (demo) location is used
  const userPos = live.latitude != null && live.longitude != null && isInTurkey(live.latitude, live.longitude)
    ? { latitude: live.latitude, longitude: live.longitude }
    : null
  const origin = userPos ?? { latitude: fallback.latitude, longitude: fallback.longitude }
  const originRef = useRef(origin)
  originRef.current = origin

  const [map, setMap] = useState<L.Map | null>(null)
  const [follow, setFollow] = useState(true)
  const [places, setPlaces] = useState<NearbyPlace[]>([])
  const [zoomedOut, setZoomedOut] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)
  const [selected, setSelected] = useState<Place | null>(null)
  const [panelOpen, setPanelOpen] = useState(true)
  const containerRef = useRef<HTMLDivElement>(null)
  const requests = useMemo(latestRequest, [])

  // ---------- map size (view switches, rotating the phone, the panel opening) ----------
  useEffect(() => {
    if (!map || !containerRef.current) return
    const fix = () => map.invalidateSize()
    const frame = requestAnimationFrame(fix)
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(fix)
    observer?.observe(containerRef.current)
    return () => {
      cancelAnimationFrame(frame)
      observer?.disconnect()
    }
  }, [map])

  // ---------- places in the visible area ----------
  const load = useCallback(() => {
    if (!map) return
    const bounds = map.getBounds()
    const box = placesQueryBox(map.getZoom(), {
      south: bounds.getSouth(), west: bounds.getWest(), north: bounds.getNorth(), east: bounds.getEast(),
    })
    if (!box) {
      requests.cancel()
      setZoomedOut(true)
      setPlaces([])
      setLoading(false)
      setError(false)
      return
    }
    setZoomedOut(false)
    const { id, signal } = requests.start()
    setLoading(true)
    setError(false)
    const { latitude, longitude } = originRef.current
    api.placesInArea(box, { lat: latitude, lon: longitude, category: category ?? undefined, tag: tag ?? undefined, limit: MAX_MAP_PINS }, signal)
      .then(result => {
        if (requests.isLatest(id)) setPlaces(result.slice(0, MAX_MAP_PINS))
      })
      .catch(() => {
        if (requests.isLatest(id)) setError(true)
      })
      .finally(() => {
        if (requests.isLatest(id)) setLoading(false)
      })
  }, [map, category, tag, requests])

  useEffect(() => {
    if (!map) return
    let timer: ReturnType<typeof setTimeout> | undefined
    const schedule = () => {
      clearTimeout(timer)
      timer = setTimeout(load, FETCH_DEBOUNCE_MS)
    }
    // Dragging by hand ends follow mode; programmatic moves (centering, flyTo) do not fire dragstart
    const stopFollowing = () => setFollow(false)
    const deselect = () => setSelected(null)
    map.on('moveend', schedule)
    map.on('dragstart', stopFollowing)
    map.on('click', deselect)
    schedule() // first load, and again whenever the category changes
    return () => {
      clearTimeout(timer)
      map.off('moveend', schedule)
      map.off('dragstart', stopFollowing)
      map.off('click', deselect)
      requests.cancel()
    }
  }, [map, load, requests])

  // ---------- follow mode ----------
  const hadFix = useRef(false)
  useEffect(() => {
    if (!map || !userPos || !follow) return
    const here = L.latLng(userPos.latitude, userPos.longitude)
    if (!hadFix.current) {
      hadFix.current = true
      map.setView(here, Math.max(map.getZoom(), 16))
    } else if (!map.getBounds().pad(-0.25).contains(here)) {
      // Only pan once the dot nears the edge: fewer reloads while walking
      map.panTo(here)
    }
  }, [map, follow, userPos?.latitude, userPos?.longitude])

  // ---------- chosen district ----------
  useEffect(() => {
    if (!map || !focusArea) return
    setFollow(false)
    hadFix.current = true // a later GPS fix must not pull the map away from the district
    const bounds = L.latLngBounds([focusArea.south, focusArea.west], [focusArea.north, focusArea.east])
    // Big districts fit only below the zoom where places load: then show their centre close enough to see places
    const fitZoom = map.getBoundsZoom(bounds)
    if (fitZoom >= MIN_PLACES_ZOOM) map.flyToBounds(bounds, { duration: 0.8 })
    else map.flyTo([focusArea.latitude, focusArea.longitude], MIN_PLACES_ZOOM, { duration: 0.8 })
  }, [map, focusArea?.slug])

  // ---------- chosen city ----------
  const shownCity = useRef<string | null>(null)
  useEffect(() => {
    if (!map || !focusCity || focusArea) return
    const first = shownCity.current == null
    shownCity.current = focusCity.slug
    // Opening the map in the user's own city keeps the "around me" view
    if (first && inBox(focusCity, originRef.current.latitude, originRef.current.longitude)) return
    setFollow(false)
    hadFix.current = true // a later GPS fix must not pull the map away from the city
    map.flyTo([focusCity.latitude, focusCity.longitude], MIN_PLACES_ZOOM, { duration: 0.8 })
  }, [map, focusCity?.slug, focusArea?.slug])

  function centerOnMe() {
    if (!map) return
    const target = userPos ?? origin
    map.setView([target.latitude, target.longitude], Math.max(map.getZoom(), 16))
    setFollow(userPos != null)
  }

  // ---------- recommendations ----------
  const [anchor, setAnchor] = useState(origin)
  useEffect(() => {
    if (haversineMeters(anchor.latitude, anchor.longitude, origin.latitude, origin.longitude) > RECOMMENDATION_REFRESH_METERS) {
      setAnchor(origin)
    }
  }, [origin.latitude, origin.longitude])
  // Only close-by picks here; "better but farther" places are offered by the assistant
  const recs = useAsync(() => api.recommendations(anchor.latitude, anchor.longitude), [anchor.latitude, anchor.longitude])

  function focus(place: Place) {
    setSelected(place)
    setFollow(false)
    setPanelOpen(false)
    map?.flyTo([place.latitude, place.longitude], Math.max(map.getZoom(), 16), { duration: 0.8 })
  }

  // ---------- pins ----------
  const pins = useMemo<Place[]>(() => {
    if (!selected || places.some(p => p.id === selected.id)) return places
    return [...places, selected]
  }, [places, selected])

  const selectedDistance = selected
    ? userPos ? haversineMeters(userPos.latitude, userPos.longitude, selected.latitude, selected.longitude) : selected.distanceMeters
    : undefined

  const statusNote = userPos ? null
    : live.status === 'locating' ? t('Konumun bulunuyor…', 'Finding your location…')
      : live.status === 'denied' ? locationLabel('demo-denied')
        : live.latitude != null ? locationLabel('demo-outside')
          : live.status === 'unavailable' ? locationLabel('demo-unavailable') : null

  return (
    <div className="live-map" ref={containerRef}>
      <MapContainer ref={setMap} center={[origin.latitude, origin.longitude]} zoom={15} zoomControl={false}
                    attributionControl={false} style={{ height: '100%' }}>
        <TileLayer attribution={TILE_ATTRIBUTION} url={TILE_URL} />
        {/* Top right: the bottom panel must never cover the OpenStreetMap attribution */}
        <AttributionControl position="topright" />
        {userPos && live.accuracy != null && live.accuracy < MAX_ACCURACY_CIRCLE && (
          <Circle center={[userPos.latitude, userPos.longitude]} radius={live.accuracy} interactive={false}
                  pathOptions={{ color: '#0e7c86', weight: 1, fillColor: '#0e7c86', fillOpacity: 0.12 }} />
        )}
        {userPos && (
          <Marker position={[userPos.latitude, userPos.longitude]} icon={liveIcon} interactive={false} zIndexOffset={1000} />
        )}
        {pins.map(place => {
          const isSelected = selected?.id === place.id
          return (
            <Marker key={place.id} position={[place.latitude, place.longitude]}
                    icon={placeIcon(place.category, place.verified, isSelected)}
                    zIndexOffset={isSelected ? 900 : 0}
                    title={place.name} alt={place.name}
                    eventHandlers={{ click: () => setSelected(place) }} />
          )
        })}
      </MapContainer>

      <div className="live-map-top">
        {zoomedOut ? (
          <span className="map-hint"><ZoomIn size={14} /> {t('Mekanları görmek için yakınlaştır', 'Zoom in to see places')}</span>
        ) : error ? (
          <button className="map-hint" onClick={load}>{t('Mekanlar yüklenemedi · tekrar dene', 'Couldn’t load places · try again')}</button>
        ) : loading ? (
          <span className="map-hint"><Spinner size={14} /> {t('Mekanlar yükleniyor', 'Loading places')}</span>
        ) : null}
        {statusNote && <span className="map-hint map-hint-soft">{statusNote}</span>}
      </div>

      <div className="live-map-bottom">
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <button className={`map-fab ${follow && userPos ? 'active' : ''}`} onClick={centerOnMe} aria-pressed={follow && userPos != null}
                  aria-label={t('Konumuma git', 'Center on me')}>
            <LocateFixed size={18} /> {t('Konumuma git', 'Center on me')}
          </button>
        </div>

        {selected && (
          <div className="card map-card" role="dialog" aria-label={selected.name}>
            <div className="row" style={{ alignItems: 'flex-start' }}>
              {selected.image && <CategoryTile category={selected.category} size={22} image={selected.image} alt={selected.name} className="map-card-photo" />}
              <div className="grow stack-sm">
                <span className="place-name">{selected.name}</span>
                <div className="meta">
                  <span>{CATEGORY_LABELS[selected.category]}</span>
                  {selectedDistance != null && <span><MapPin size={12} />{formatDistance(selectedDistance)}</span>}
                </div>
              </div>
              <button className="icon-btn icon-btn-plain" style={{ width: 34, height: 34 }} aria-label={t('Kapat', 'Close')}
                      onClick={() => setSelected(null)}>
                <X size={18} />
              </button>
            </div>
            <div className="row wrap" style={{ gap: 6 }}>
              {selected.openNow == null
                ? <span className="badge">{t('Saat bilgisi yok', 'Hours unknown')}</span>
                : <OpenBadge openNow={selected.openNow} />}
              <span className="badge">{formatCost(selected.estimatedCost)}</span>
              {selected.verified && <VerifiedBadge />}
            </div>
            <Link to={`/places/${selected.id}`} className="btn btn-primary btn-block">{t('Mekanı gör', 'View place')}</Link>
          </div>
        )}

        <section className={`card map-panel ${panelOpen ? 'open' : ''}`} aria-label={t('Şu an senin için', 'Right now for you')}>
          <button className="map-panel-head" onClick={() => setPanelOpen(o => !o)} aria-expanded={panelOpen}>
            <Sparkles size={16} color="var(--brand)" />
            <span className="grow">{t('Şu an senin için', 'Right now for you')}</span>
            {panelOpen ? <ChevronDown size={18} /> : <ChevronUp size={18} />}
          </button>
          {panelOpen && (
            <div className="map-panel-body">
              {recs.loading && <><Skeleton height={56} radius={14} /><Skeleton height={56} radius={14} /></>}
              {recs.error && (
                <p className="t-caption">
                  {t('Öneriler yüklenemedi.', 'Couldn’t load suggestions.')}{' '}
                  <button className="section-link" onClick={() => void recs.reload()}>{t('Tekrar dene', 'Try again')}</button>
                </p>
              )}
              {recs.data && recs.data.length === 0 && (
                <p className="t-caption">{t('Yakınında şu an açık bir öneri bulamadım.', 'I couldn’t find anything open near you right now.')}</p>
              )}
              {recs.data?.map(r => <RecItem key={r.place.id} rec={r} onSelect={focus} />)}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}

function RecItem({ rec, onSelect }: { rec: Recommendation; onSelect: (place: Place) => void }) {
  const { place } = rec
  return (
    <button className="rec-item" onClick={() => onSelect(place)}>
      <CategoryTile category={place.category} size={20} image={place.image} alt={place.name} />
      <span className="grow stack-sm" style={{ gap: 2 }}>
        <span className="place-name" style={{ fontSize: 15 }}>{place.name}</span>
        <span className="meta">
          <span>{CATEGORY_LABELS[place.category]}</span>
          {place.distanceMeters != null && <span>{formatDistance(place.distanceMeters)}</span>}
          <span>{formatCost(place.estimatedCost)}</span>
        </span>
      </span>
    </button>
  )
}
