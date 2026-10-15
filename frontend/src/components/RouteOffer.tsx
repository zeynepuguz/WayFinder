import { Check, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useLocation } from 'react-router'
import { api } from '../api'
import { errorMessage, formatDate, formatTime } from '../lib/format'
import { useT } from '../lib/i18n'
import { offerRoute, useRouteOffer } from '../lib/routeOffer'
import { Alert, Sheet, Spinner, useToast } from './ui'

/**
 * After leaving the assistant: the route it planned in that chat, with "Rotalarıma ekle" and "Sil". Closing the
 * sheet keeps the route in its chat only. Not shown on the assistant itself, nor on that route's own page (it has
 * its own "Ekle").
 */
export function RouteOffer() {
  const route = useRouteOffer()
  const { pathname } = useLocation()
  const t = useT()
  const toast = useToast()
  const [busy, setBusy] = useState<'add' | 'delete' | null>(null)
  const [error, setError] = useState<string | null>(null)

  const onItsPage = route != null && pathname === `/routes/${route.id}`
  useEffect(() => {
    if (onItsPage) offerRoute(null)
  }, [onItsPage])
  useEffect(() => setError(null), [route?.id])

  const open = route != null && !onItsPage && !pathname.startsWith('/assistant')

  async function run(kind: 'add' | 'delete') {
    if (!route) return
    setBusy(kind)
    setError(null)
    try {
      if (kind === 'add') {
        await api.updateRoute(route.id, { listed: true })
        toast(t('Rotalarım’a eklendi', 'Added to My routes'))
      } else {
        await api.deleteRoute(route.id)
        toast(t('Rota silindi', 'Route deleted'))
      }
      offerRoute(null)
    } catch (e) {
      setError(errorMessage(e, t('İşlem yapılamadı', 'Something went wrong')))
    } finally {
      setBusy(null)
    }
  }

  return (
    <Sheet open={open} onClose={() => offerRoute(null)} label={t('Asistanın oluşturduğu rota', 'The route the assistant made')}>
      {route && (
        <div className="stack">
          <div className="stack-sm" style={{ gap: 2 }}>
            <strong className="t-headline">{route.title}</strong>
            <span className="t-caption">{formatDate(route.date)}</span>
          </div>
          <ol className="offer-stops">
            {route.stops.map(stop => (
              <li key={stop.id}><span className="time">{formatTime(stop.plannedStart)}</span>{stop.place.name}</li>
            ))}
          </ol>
          <p className="ink-2">{t('Bu rota Rotalarım’a eklensin mi?', 'Add this route to My routes?')}</p>
          {error && <Alert tone="danger"><span>{error}</span></Alert>}
          <div className="row" style={{ gap: 8 }}>
            <button type="button" className="btn btn-danger grow" disabled={busy !== null} onClick={() => void run('delete')}>
              {busy === 'delete' ? <Spinner /> : <><Trash2 size={18} /> {t('Sil', 'Delete')}</>}
            </button>
            <button type="button" className="btn btn-primary grow" disabled={busy !== null} onClick={() => void run('add')}>
              {busy === 'add' ? <Spinner /> : <><Check size={18} /> {t('Rotalarıma ekle', 'Add to My routes')}</>}
            </button>
          </div>
        </div>
      )}
    </Sheet>
  )
}
