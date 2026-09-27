import type { Place, PlaceImage } from '../api/types'

export const PHOTO: PlaceImage = {
  url: 'https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Moda.jpg/800px-Moda.jpg',
  author: 'Jane Doe',
  license: 'CC BY-SA 4.0',
  sourceUrl: 'https://commons.wikimedia.org/wiki/File:Moda.jpg',
}

export const place = (id: number, name: string, extra: Partial<Place> = {}): Place => ({
  id, name, description: null, address: null, neighborhood: null, latitude: 40.99, longitude: 29.02,
  category: 'CAFE', estimatedCost: null, rating: null, indoor: false, avgVisitMinutes: null, tags: [],
  openingHours: [], openNow: null, source: 'OSM', lastVerifiedAt: null, verified: false,
  sourceUrl: null, image: null, ...extra,
})
