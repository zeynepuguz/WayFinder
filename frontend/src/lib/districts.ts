import { api } from '../api'
import type { District } from '../api/types'
import { fold } from './format'
import { useAsync } from './useAsync'

// A city's district list rarely changes: loaded once per city per app start and shared by every screen
const cache = new Map<string, Promise<District[]>>()

export function loadDistricts(city: string): Promise<District[]> {
  let list = cache.get(city)
  if (!list) {
    list = api.districts(city).catch(error => {
      cache.delete(city) // a failed load may be retried
      throw error
    })
    cache.set(city, list)
  }
  return list
}

/** Test helper: forget the cached lists */
export function resetDistrictCache() {
  cache.clear()
}

/** Districts of the city (data is null while that city's list is loading; never another city's list) */
export function useDistricts(city: string | null) {
  const result = useAsync(async () => (city ? { city, list: await loadDistricts(city) } : null), [city])
  return {
    ...result,
    data: result.data && result.data.city === city ? result.data.list : null,
  }
}

/** Items (districts, cities) whose name matches the search (case- and diacritic-insensitive), keeping the server order */
export function filterByName<T extends { name: string }>(items: T[], query: string): T[] {
  const q = fold(query.trim())
  return q ? items.filter(item => fold(item.name).includes(q)) : items
}

export function filterDistricts(districts: District[], query: string): District[] {
  return filterByName(districts, query)
}
