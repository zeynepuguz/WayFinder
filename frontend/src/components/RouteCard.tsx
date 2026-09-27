import { ChevronRight, Route as RouteIcon } from 'lucide-react'
import { Link } from 'react-router'
import type { RouteSummary } from '../api/types'
import { formatDate } from '../lib/format'

const STATUS: Record<RouteSummary['status'], { label: string; tone: string }> = {
  DRAFT: { label: 'Planlandı', tone: 'badge-sea' },
  ACTIVE: { label: 'Devam ediyor', tone: 'badge-success' },
  COMPLETED: { label: 'Tamamlandı', tone: '' },
}

export function RouteCard({ route }: { route: RouteSummary }) {
  const status = STATUS[route.status]
  return (
    <Link to={`/routes/${route.id}`} className="card route-card card-press">
      <div className="route-thumb"><RouteIcon size={26} /></div>
      <div className="grow stack-sm">
        <div className="row" style={{ gap: 8 }}>
          <span className="place-name">{route.title}</span>
        </div>
        <div className="meta">
          <span>{formatDate(route.date)}</span>
          <span>{route.stopCount} durak</span>
          <span>~{route.totalEstimatedCost.toLocaleString('tr-TR')} TL</span>
        </div>
        <div><span className={`badge ${status.tone}`}>{status.label}</span></div>
      </div>
      <ChevronRight size={20} className="muted" />
    </Link>
  )
}
