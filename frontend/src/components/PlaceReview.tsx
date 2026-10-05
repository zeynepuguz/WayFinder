import { AlertTriangle, EyeOff, Flag, RotateCcw, ShieldCheck } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { api } from '../api'
import type { Place, ReportReason, ReviewAction } from '../api/types'
import { useAuth } from '../context/AuthContext'
import { useT } from '../lib/i18n'
import { Sheet, useToast } from './ui'

/**
 * Under a place: "Bildir" for every user (the owner sees the reports in the admin area) and, for the owner only,
 * Kaldır / Şüpheli / Normale döndür.
 */
export function PlaceReportAndReview({ place, onChanged }: { place: Place; onChanged: () => void }) {
  const t = useT()
  const { user } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [reportOpen, setReportOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  const openReport = () => {
    if (!user) {
      navigate(`/login?next=${encodeURIComponent(`/places/${place.id}`)}`)
      return
    }
    setReportOpen(true)
  }

  const report = async (reason: ReportReason) => {
    setReportOpen(false)
    try {
      await api.reportPlace(place.id, reason)
      toast(t('Bildirimin için teşekkürler, kontrol edeceğiz.', 'Thanks for letting us know, we will check it.'))
    } catch (e) {
      toast(e instanceof Error ? e.message : t('Bildirim gönderilemedi', 'Could not send the report'))
    }
  }

  const review = async (action: ReviewAction) => {
    if (action === 'REMOVE' && !window.confirm(t('Bu mekan kullanıcılardan kaldırılsın mı?', 'Remove this place for users?'))) return
    setBusy(true)
    try {
      await api.reviewPlace(place.id, action)
      if (action === 'REMOVE') {
        toast(t('Mekan kaldırıldı; Yönetim > Silinen mekanlar altında.', 'Place removed; see Admin > Removed places.'))
        navigate(-1)
        return
      }
      toast(action === 'SUSPECT' ? t('Şüpheli olarak işaretlendi', 'Marked as suspect') : t('İşaret kaldırıldı', 'Mark removed'))
      onChanged()
    } catch (e) {
      toast(e instanceof Error ? e.message : t('İşlem yapılamadı', 'Could not do that'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <button type="button" className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start' }} onClick={openReport}>
        <Flag size={15} /> {t('Bu mekanı bildir', 'Report this place')}
      </button>

      {user?.role === 'ADMIN' && (
        <section className="card stack-sm" aria-label={t('Yönetim', 'Admin')}>
          <h2 className="t-headline row" style={{ gap: 6 }}><ShieldCheck size={18} /> {t('Yönetim', 'Admin')}</h2>
          <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
            <button type="button" className="btn btn-danger btn-sm" disabled={busy} onClick={() => void review('REMOVE')}>
              <EyeOff size={15} /> {t('Mekanı kaldır', 'Remove place')}
            </button>
            {place.suspect ? (
              <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void review('CLEAR')}>
                <RotateCcw size={15} /> {t('Şüpheli işaretini kaldır', 'Clear suspect mark')}
              </button>
            ) : (
              <button type="button" className="btn btn-secondary btn-sm" disabled={busy} onClick={() => void review('SUSPECT')}>
                <AlertTriangle size={15} /> {t('Şüpheli olarak işaretle', 'Mark as suspect')}
              </button>
            )}
          </div>
        </section>
      )}

      <Sheet open={reportOpen} onClose={() => setReportOpen(false)} label={t('Bu mekanı bildir', 'Report this place')}>
        <div className="list-group">
          <button type="button" className="list-item" onClick={() => void report('CLOSED')}>
            {t('Bu mekanın kapandığını düşünüyorum', 'I think this place has closed')}
          </button>
          <button type="button" className="list-item" onClick={() => void report('WRONG_LOCATION')}>
            {t('Konumu yanlış', 'The location is wrong')}
          </button>
        </div>
      </Sheet>
    </>
  )
}
