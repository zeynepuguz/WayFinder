import 'leaflet/dist/leaflet.css'
import L from 'leaflet'
import { useEffect } from 'react'
import { MapContainer, Marker, Polyline, Popup, TileLayer, useMap } from 'react-leaflet'
import { useT } from '../lib/i18n'
import { TILE_ATTRIBUTION, TILE_URL } from './mapTiles'

interface MapPoint {
  latitude: number
  longitude: number
  label: string
  title: string
  muted?: boolean
}

interface Props {
  points: MapPoint[]
  start?: { latitude: number; longitude: number }
  height?: number
  hero?: boolean
}

// Pins are drawn with CSS, so no marker image files need to be bundled. Cached: a new icon object on every
// render would make Leaflet replace each marker's element (labels are stop numbers, so the cache stays small)
const iconCache = new Map<string, L.DivIcon>()
function pinIcon(label: string, muted = false): L.DivIcon {
  const key = `${label}-${muted}`
  let icon = iconCache.get(key)
  if (!icon) {
    icon = L.divIcon({
      className: '',
      html: `<div class="map-pin ${muted ? 'map-pin-muted' : ''}"><span>${label}</span></div>`,
      iconSize: [30, 30],
      iconAnchor: [15, 30],
      popupAnchor: [0, -28],
    })
    iconCache.set(key, icon)
  }
  return icon
}

const startIcon = L.divIcon({ className: '', html: '<div class="map-start"></div>', iconSize: [18, 18], iconAnchor: [9, 9] })

function FitBounds({ coords }: { coords: [number, number][] }) {
  const map = useMap()
  const key = JSON.stringify(coords)
  useEffect(() => {
    if (coords.length === 1) map.setView(coords[0], 16)
    else if (coords.length > 1) map.fitBounds(L.latLngBounds(coords), { padding: [36, 36] })
    // key captures coordinate changes
  }, [map, key])
  return null
}

export function RouteMap({ points, start, height = 260, hero }: Props) {
  const t = useT()
  const coords: [number, number][] = points.map(p => [p.latitude, p.longitude])
  const all: [number, number][] = start ? [[start.latitude, start.longitude], ...coords] : coords
  const center = all[0] ?? [40.991, 29.023]

  return (
    <div className={`map ${hero ? 'map-hero' : ''}`} style={{ height }}>
      <MapContainer center={center} zoom={15} scrollWheelZoom={false} zoomControl={false} style={{ height: '100%' }}>
        <TileLayer
          attribution={TILE_ATTRIBUTION}
          url={TILE_URL}
        />
        {start && <Marker position={[start.latitude, start.longitude]} icon={startIcon}><Popup>{t('Başlangıç', 'Start')}</Popup></Marker>}
        {all.length > 1 && <Polyline positions={all} pathOptions={{ color: '#ff5a36', weight: 4, opacity: 0.85, dashArray: '2 8', lineCap: 'round' }} />}
        {points.map((p, i) => (
          <Marker key={`${i}-${p.title}`} position={[p.latitude, p.longitude]} icon={pinIcon(p.label, p.muted)}>
            <Popup>{p.title}</Popup>
          </Marker>
        ))}
        <FitBounds coords={all} />
      </MapContainer>
    </div>
  )
}
