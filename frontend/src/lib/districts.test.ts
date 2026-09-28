import type { District } from '../api/types'
import { filterByName, filterDistricts, loadDistricts, resetDistrictCache } from './districts'
import { appendUnique, fold } from './format'

const districts = vi.fn()
vi.mock('../api', () => ({ api: { districts: (city: string) => districts(city) } }))

const d = (slug: string, name: string): District =>
  ({ slug, name, latitude: 41, longitude: 29, south: 40.9, west: 28.9, north: 41.1, east: 29.1, placeCount: 10 })

describe('districts', () => {
  beforeEach(() => {
    resetDistrictCache()
    districts.mockReset()
  })

  it('loads the list of each city once and retries after a failure', async () => {
    districts.mockRejectedValueOnce(new Error('offline')).mockResolvedValue([d('adalar', 'Adalar')])
    await expect(loadDistricts('istanbul')).rejects.toThrow('offline')
    expect(await loadDistricts('istanbul')).toHaveLength(1)
    await loadDistricts('istanbul')
    expect(districts).toHaveBeenCalledTimes(2)

    await loadDistricts('ankara')
    await loadDistricts('ankara')
    expect(districts).toHaveBeenCalledTimes(3)
    expect(districts).toHaveBeenLastCalledWith('ankara')
  })

  it('matches names regardless of case and Turkish letters, keeping the order', () => {
    const list = [d('besiktas', 'Beşiktaş'), d('sisli', 'Şişli'), d('uskudar', 'Üsküdar')]
    expect(filterDistricts(list, 'sis').map(x => x.slug)).toEqual(['sisli'])
    expect(filterDistricts(list, 'ÜSK').map(x => x.slug)).toEqual(['uskudar'])
    expect(filterDistricts(list, '  ')).toBe(list)
    expect(fold('KADIKÖY')).toBe('kadikoy')
  })

  it('searches city names the same way', () => {
    const cities = [{ name: 'Çanakkale' }, { name: 'Iğdır' }, { name: 'İzmir' }]
    expect(filterByName(cities, 'canak')).toEqual([{ name: 'Çanakkale' }])
    expect(filterByName(cities, 'IGDIR')).toEqual([{ name: 'Iğdır' }])
    expect(filterByName(cities, 'izm')).toEqual([{ name: 'İzmir' }])
  })
})

describe('appendUnique', () => {
  it('adds only new ids', () => {
    const a = [{ id: 1 }, { id: 2 }]
    expect(appendUnique(a, [{ id: 2 }, { id: 3 }, { id: 3 }]).map(x => x.id)).toEqual([1, 2, 3])
    expect(appendUnique(a, [{ id: 1 }])).toBe(a)
  })
})
