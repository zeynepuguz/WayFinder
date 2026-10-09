// Base map. Default: OpenFreeMap vector maps (OpenStreetMap data; free, commercial use allowed, no key), drawn with
// MapLibre under the Leaflet markers, light or dark like the app. VITE_MAP_STYLE_URL / _DARK can point at another
// MapLibre style (e.g. our own server later). Devices without WebGL get raster tiles: VITE_MAP_TILE_URL, else the
// public OpenStreetMap server (only acceptable for those few devices). The attribution is required by the
// tile/data licenses and must stay visible on every map.
const env = import.meta.env

export const VECTOR_STYLE_LIGHT: string = env.VITE_MAP_STYLE_URL || 'https://tiles.openfreemap.org/styles/positron'
export const VECTOR_STYLE_DARK: string = env.VITE_MAP_STYLE_URL_DARK || 'https://tiles.openfreemap.org/styles/dark'
export const VECTOR_ATTRIBUTION = '<a href="https://openfreemap.org" target="_blank" rel="noopener">OpenFreeMap</a> '
  + '&copy; <a href="https://www.openmaptiles.org/" target="_blank" rel="noopener">OpenMapTiles</a> '
  + 'Data from <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>'

export const TILE_URL: string = env.VITE_MAP_TILE_URL || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
export const TILE_ATTRIBUTION: string = env.VITE_MAP_ATTRIBUTION
  || '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'

// MapLibre draws with WebGL; a few old devices / browsers (and the test DOM) have none
export function webglSupported(): boolean {
  try {
    const canvas = document.createElement('canvas')
    return Boolean(canvas.getContext('webgl2') ?? canvas.getContext('webgl'))
  } catch {
    return false
  }
}
