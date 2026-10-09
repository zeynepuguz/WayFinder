// MapLibre (~280 kB gzip) in its own chunk: downloaded only when a map is on screen (BaseMap)
import 'maplibre-gl/dist/maplibre-gl.css'
import { maplibreGL } from '@maplibre/maplibre-gl-leaflet'
import { setWorkerUrl } from 'maplibre-gl'
// MapLibre 6 draws tiles in a module worker next to its main file; after bundling it cannot find it by itself,
// so Vite bundles the worker and hands over its URL
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'

setWorkerUrl(workerUrl)

export function vectorLayer(style: string, attribution: string) {
  return maplibreGL({ style, attributionControl: { customAttribution: attribution } })
}
