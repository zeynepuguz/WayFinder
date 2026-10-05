import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router'
import type { Route } from '../../api/types'
import { formatCost, formatTime, routeTotalCost } from '../../lib/format'
import { useT } from '../../lib/i18n'
import { STOP_ICON } from '../visuals'

/** The route an assistant answer created: its next stops, linking to the route page. */
export function RoutePreview({ route }: { route: Route }) {
  const t = useT()
  const upcoming = route.stops.filter(s => s.status === 'PLANNED')
  return (
    <Link to={`/routes/${route.id}`} className="card route-mini card-press">
      <div className="row-between">
        <span className="t-headline" style={{ fontSize: 15 }}>{route.title}</span>
        <span className="badge badge-brand">
          {routeTotalCost(route)}
        </span>
      </div>
      <ol>
        {upcoming.slice(0, 7).map(stop => {
          const Icon = STOP_ICON[stop.type]
          return (
            <li key={stop.id}>
              <span className="time">{formatTime(stop.plannedStart)}</span>
              <span className="stop-icon"><Icon size={15} /></span>
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {stop.place.name} <span className="muted" style={{ fontWeight: 500 }}>· {formatCost(stop.place.estimatedCost)}</span>
              </span>
            </li>
          )
        })}
      </ol>
      <span className="section-link">{t('Haritada gör', 'View on map')} <ChevronRight size={16} /></span>
    </Link>
  )
}
