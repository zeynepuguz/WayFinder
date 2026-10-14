import { ThumbsDown, ThumbsUp } from 'lucide-react'
import { useState } from 'react'
import { api } from '../api'
import { errorMessage } from '../lib/format'
import { useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'
import { useToast } from './ui'

// "Bu rotayı beğendin mi?" on the owner's route: liked days are offered to people asking for a similar day nearby
// and listed as the area's community routes; disliked ones never are. Tapping the chosen answer again takes it back.
export function RouteFeedback({ routeId }: { routeId: number }) {
  const t = useT()
  const toast = useToast()
  const loaded = useAsync(() => api.routeFeedback(routeId).catch(() => null), [routeId])
  const [liked, setLiked] = useState<boolean | null | undefined>(undefined)
  const [busy, setBusy] = useState(false)
  const current = liked !== undefined ? liked : loaded.data?.liked ?? null

  if (loaded.loading) return null

  async function answer(value: boolean) {
    setBusy(true)
    try {
      const result = current === value ? await api.removeRouteFeedback(routeId) : await api.setRouteFeedback(routeId, value)
      setLiked(result.liked)
    } catch (e) {
      toast(errorMessage(e, t('Cevabın kaydedilemedi', 'Could not save your answer')))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card stack-sm">
      <h2 className="t-headline">{t('Bu rotayı beğendin mi?', 'Did you like this route?')}</h2>
      <div className="row" style={{ gap: 8 }}>
        <button type="button" className={`chip ${current === true ? 'active' : ''}`} aria-pressed={current === true}
                disabled={busy} onClick={() => void answer(true)}>
          <ThumbsUp size={16} /> {t('Beğendim', 'I liked it')}
        </button>
        <button type="button" className={`chip ${current === false ? 'active' : ''}`} aria-pressed={current === false}
                disabled={busy} onClick={() => void answer(false)}>
          <ThumbsDown size={16} /> {t('Beğenmedim', 'Not really')}
        </button>
      </div>
      <span className="t-caption">
        {current === true
          ? t('Teşekkürler! Yakında benzer bir gün planlayanlara da bu rota önerilecek.', 'Thanks! People planning a similar day nearby will get this route too.')
          : current === false
            ? t('Teşekkürler, bu rotayı başkalarına önermeyeceğiz.', 'Thanks, we won’t suggest this route to others.')
            : t('Cevabın, benzer bir gün planlayanlara hangi rotanın önerileceğini belirler.', 'Your answer decides which routes people planning a similar day get.')}
      </span>
    </section>
  )
}
