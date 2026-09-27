// Map tiles: a commercial app needs its own tile provider/key (VITE_MAP_TILE_URL). The default is
// the public OpenStreetMap server, which is only fine for development. The attribution is required
// by the tile/data license and must stay visible on every map.
export const TILE_URL: string = import.meta.env.VITE_MAP_TILE_URL || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png'
export const TILE_ATTRIBUTION: string = import.meta.env.VITE_MAP_ATTRIBUTION
  || '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
