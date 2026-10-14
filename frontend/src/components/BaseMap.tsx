import type { Layer } from 'leaflet'
import { useEffect, useState } from 'react'
import { TileLayer, useMap } from 'react-leaflet'
import { useIsDark } from '../lib/theme'
import { TILE_ATTRIBUTION, TILE_URL, VECTOR_ATTRIBUTION, VECTOR_STYLE_DARK, VECTOR_STYLE_LIGHT, webglSupported } from './mapTiles'

// The ground of every map (inside a MapContainer): OpenFreeMap vector map in the app's light / dark look; raster
// tiles when the device has no WebGL or the vector map cannot start
export function BaseMap() {
  const map = useMap()
  const dark = useIsDark()
  const [raster, setRaster] = useState(() => !webglSupported())

  useEffect(() => {
    if (raster) return
    let layer: Layer | null = null
    let cancelled = false
    import('./vectorBase')
      .then(({ vectorLayer }) => {
        if (cancelled) return
        layer = vectorLayer(dark ? VECTOR_STYLE_DARK : VECTOR_STYLE_LIGHT, VECTOR_ATTRIBUTION).addTo(map)
      })
      .catch(() => { if (!cancelled) setRaster(true) })
    return () => {
      cancelled = true
      if (layer) map.removeLayer(layer)
    }
  }, [map, dark, raster])

  return raster ? <TileLayer attribution={TILE_ATTRIBUTION} url={TILE_URL} /> : null
}
