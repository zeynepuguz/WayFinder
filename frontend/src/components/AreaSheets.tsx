import { Check, MapPin, Search } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { City, District } from '../api/types'
import type { useCity } from '../context/CityContext'
import { filterByName, filterDistricts, type useDistricts } from '../lib/districts'
import { locale, useT } from '../lib/i18n'
import { ErrorState, Sheet, Skeleton } from './ui'

// City and district pickers shared by Explore and the new-route form.
// Every button is type="button": the sheets may be rendered inside a <form> and must never submit it.

// Bottom sheet: search field, "All districts" and every district with its number of places
export function DistrictSheet({ open, onClose, selected, districts, onChoose }: {
  open: boolean
  onClose: () => void
  selected: string | null
  districts: ReturnType<typeof useDistricts>
  onChoose: (district: District | null) => void
}) {
  const t = useT()
  const [search, setSearch] = useState('')
  useEffect(() => {
    if (!open) setSearch('')
  }, [open])

  const list = districts.data ? filterDistricts(districts.data, search) : []
  const count = (n: number) => `${n.toLocaleString(locale())} ${t('mekan', n === 1 ? 'place' : 'places')}`

  return (
    <Sheet open={open} onClose={onClose} label={t('İlçe seç', 'Choose district')}>
      <div className="stack">
        <label className="search">
          <Search size={18} />
          <input type="search" placeholder={t('İlçe ara', 'Search districts')} value={search} onChange={e => setSearch(e.target.value)}
                 aria-label={t('İlçe ara', 'Search districts')} />
        </label>

        {districts.error && <ErrorState message={districts.error} onRetry={() => void districts.reload()} />}

        <div className="list-group district-list">
          {!search.trim() && (
            <button type="button" className="list-item" aria-pressed={selected == null} onClick={() => onChoose(null)}>
              <span className="list-item-icon"><MapPin size={18} /></span>
              <span className="grow" style={{ fontWeight: 700 }}>{t('Tüm ilçeler', 'All districts')}</span>
              {selected == null && <Check size={18} color="var(--brand)" />}
            </button>
          )}
          {districts.loading && !districts.data && (
            <div className="stack" style={{ padding: 12 }}>
              {[0, 1, 2, 3].map(i => <Skeleton key={i} height={40} radius={12} />)}
            </div>
          )}
          {list.map(d => (
            <button type="button" key={d.slug} className="list-item" aria-pressed={selected === d.slug} onClick={() => onChoose(d)}>
              <span className="grow" style={{ fontWeight: 650 }}>{d.name}</span>
              <span className="t-caption">{count(d.placeCount)}</span>
              {selected === d.slug && <Check size={18} color="var(--brand)" />}
            </button>
          ))}
          {districts.data && search.trim() && list.length === 0 && (
            <p className="t-caption" style={{ padding: 16 }}>{t('Bu isimde bir ilçe yok.', 'No district with that name.')}</p>
          )}
        </div>
      </div>
    </Sheet>
  )
}

// Bottom sheet: search field and all 81 cities; cities without places yet are shown as "coming soon"
export function CitySheet({ open, onClose, selected, state, onChoose }: {
  open: boolean
  onClose: () => void
  selected: string | null
  state: ReturnType<typeof useCity>
  onChoose: (city: City) => void
}) {
  const t = useT()
  const [search, setSearch] = useState('')
  useEffect(() => {
    if (!open) setSearch('')
  }, [open])

  const list = filterByName(state.cities, search)
  const count = (n: number) => `${n.toLocaleString(locale())} ${t('mekan', n === 1 ? 'place' : 'places')}`

  return (
    <Sheet open={open} onClose={onClose} label={t('Şehir seç', 'Choose city')}>
      <div className="stack">
        <label className="search">
          <Search size={18} />
          <input type="search" placeholder={t('Şehir ara', 'Search cities')} value={search} onChange={e => setSearch(e.target.value)}
                 aria-label={t('Şehir ara', 'Search cities')} />
        </label>

        {state.error && <ErrorState message={state.error} onRetry={state.reload} />}

        <div className="list-group district-list city-list">
          {state.loading && !state.cities.length && (
            <div className="stack" style={{ padding: 12 }}>
              {[0, 1, 2, 3].map(i => <Skeleton key={i} height={40} radius={12} />)}
            </div>
          )}
          {list.map(c => {
            const soon = c.placeCount === 0
            return (
              <button type="button" key={c.slug} className="list-item" aria-pressed={selected === c.slug} disabled={soon}
                      onClick={() => onChoose(c)}>
                <span className="grow" style={{ fontWeight: 650 }}>{c.name}</span>
                {soon
                  ? <span className="badge">{t('yakında', 'coming soon')}</span>
                  : <span className="t-caption">{count(c.placeCount)}</span>}
                {selected === c.slug && <Check size={18} color="var(--brand)" />}
              </button>
            )
          })}
          {state.cities.length > 0 && search.trim() && list.length === 0 && (
            <p className="t-caption" style={{ padding: 16 }}>{t('Bu isimde bir şehir yok.', 'No city with that name.')}</p>
          )}
        </div>
      </div>
    </Sheet>
  )
}
