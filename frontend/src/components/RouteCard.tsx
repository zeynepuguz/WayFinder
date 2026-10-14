import { ArrowRight, ChevronRight, Route as RouteIcon } from 'lucide-react'
import { Link } from 'react-router'
import type { RouteSummary } from '../api/types'
import { formatDate, isPastRoute, routeCost } from '../lib/format'
import { useT } from '../lib/i18n'

const STATUS: Record<RouteSummary['status'], { label: [string, string]; tone: string }> = {
  DRAFT: { label: ['Planlandı', 'Planned'], tone: 'badge-sea' },
  ACTIVE: { label: ['Devam ediyor', 'In progress'], tone: 'badge-success' },
  COMPLETED: { label: ['Tamamlandı', 'Completed'], tone: '' },
  EXPIRED: { label: ['Geçmiş', 'Past'], tone: '' },
}

// featured: today's route at the top of Rotalarım, a bigger card with a "continue" button
export function RouteCard({ route, featured }: { route: RouteSummary; featured?: boolean }) {
  const t = useT()
  // A planned route whose day is over is shown as past, even before the server marked it EXPIRED
  const status = STATUS[route.status !== 'COMPLETED' && isPastRoute(route) ? 'EXPIRED' : route.status]
  if (featured) {
    return (
      <Link to={`/routes/${route.id}`} className="card route-featured card-press">
        <div className="row-between">
          <span className={`badge ${status.tone}`}>{t(status.label[0], status.label[1])}</span>
          {route.sharedWithMe && <span className="badge badge-brand">{t('Grup', 'Group')}</span>}
        </div>
        <span className="t-title">{route.title}</span>
        <div className="meta">
          <span>{route.stopCount} {t('durak', route.stopCount === 1 ? 'stop' : 'stops')}</span>
          <span>{routeCost(route.totalEstimatedCost, route.costKnown)}</span>
        </div>
        {route.startLabel && <span className="t-caption">{t('Başlangıç', 'Start')}: {route.startLabel}</span>}
        <span className="btn btn-primary btn-block">
          {route.status === 'ACTIVE' ? t('Devam et', 'Continue') : t('Rotayı aç', 'Open route')} <ArrowRight size={18} />
        </span>
      </Link>
    )
  }
  return (
    <Link to={`/routes/${route.id}`} className="card route-card card-press">
      <div className="route-thumb"><RouteIcon size={26} /></div>
      <div className="grow stack-sm">
        <div className="row" style={{ gap: 8 }}>
          <span className="place-name">{route.title}</span>
        </div>
        <div className="meta">
          <span>{formatDate(route.date)}</span>
          <span>{route.stopCount} {route.stopCount === 1 ? t('durak', 'stop') : t('durak', 'stops')}</span>
          <span>{routeCost(route.totalEstimatedCost, route.costKnown)}</span>
        </div>
        {route.startLabel && <span className="t-caption">{t('Başlangıç', 'Start')}: {route.startLabel}</span>}
        <div className="row" style={{ gap: 6 }}>
          <span className={`badge ${status.tone}`}>{t(status.label[0], status.label[1])}</span>
          {route.sharedWithMe && <span className="badge badge-brand">{t('Grup', 'Group')}</span>}
        </div>
      </div>
      <ChevronRight size={20} className="muted" />
    </Link>
  )
}
