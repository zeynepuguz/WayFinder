import { Star, ThumbsDown, ThumbsUp } from 'lucide-react'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { api } from '../api'
import type { PlaceRating as Rating, RatingReason } from '../api/types'
import { useAuth } from '../context/AuthContext'
import { errorMessage } from '../lib/format'
import { tr, useT } from '../lib/i18n'
import { useAsync } from '../lib/useAsync'
import { Spinner, useToast } from './ui'

// "Neden bu puanı verdin?" answers: liked ones fit 4-5 stars, disliked ones 1-2, both 3 (backend RatingReason)
const REASONS: Record<RatingReason, { positive: boolean; label: [string, string] }> = {
  TASTY: { positive: true, label: ['Lezzetli', 'Tasty'] },
  AFFORDABLE: { positive: true, label: ['Uygun fiyatlı', 'Good value'] },
  VIEW: { positive: true, label: ['Manzarası güzel', 'Nice view'] },
  LOCAL_FLAVOURS: { positive: true, label: ['Yöresel lezzetler', 'Local flavours'] },
  GREAT_COFFEE: { positive: true, label: ['Kahvesi güzel', 'Great coffee'] },
  GREAT_BREAKFAST: { positive: true, label: ['Kahvaltısı güzel', 'Great breakfast'] },
  GREAT_DESSERT: { positive: true, label: ['Tatlısı güzel', 'Great desserts'] },
  FAMILY_FRIENDLY: { positive: true, label: ['Çocuklarla rahat', 'Good with children'] },
  QUIET: { positive: true, label: ['Sakin', 'Quiet'] },
  FRIENDLY_STAFF: { positive: true, label: ['Güler yüzlü çalışanlar', 'Friendly staff'] },
  CLEAN: { positive: true, label: ['Temiz', 'Clean'] },
  NOT_TASTY: { positive: false, label: ['Lezzetsiz', 'Not tasty'] },
  EXPENSIVE: { positive: false, label: ['Pahalı', 'Expensive'] },
  CROWDED_NOISY: { positive: false, label: ['Kalabalık, gürültülü', 'Crowded, noisy'] },
  SLOW_SERVICE: { positive: false, label: ['Servis yavaş', 'Slow service'] },
  NOT_CLEAN: { positive: false, label: ['Temiz değil', 'Not clean'] },
  UNFRIENDLY_STAFF: { positive: false, label: ['İlgisiz çalışanlar', 'Unfriendly staff'] },
}
const MAX_REASONS = 4

export function reasonLabel(reason: RatingReason): string {
  return tr(...REASONS[reason].label)
}

function reasonsFor(stars: number): RatingReason[] {
  return (Object.keys(REASONS) as RatingReason[]).filter(r => stars === 3 || REASONS[r].positive === stars >= 4)
}

// Place page: Nomi users' stars for a food or drink place, the visitor's own (an account is enough, no pass),
// then "why?": the answers tell the planner what the place is good for
export function PlaceRating({ placeId }: { placeId: number }) {
  const t = useT()
  const toast = useToast()
  const { user } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const loaded = useAsync(() => api.placeRating(placeId).catch(() => null), [placeId, user?.id])
  const [changed, setChanged] = useState<Rating | null>(null)
  const [busy, setBusy] = useState(false)
  // The "why" step: open right after rating, or from "Nedenini değiştir"
  const [asking, setAsking] = useState(false)
  const [chosen, setChosen] = useState<RatingReason[]>([])

  const rating = changed ?? loaded.data
  if (!rating || !rating.rateable) return null

  async function rate(stars: number) {
    if (!user) {
      navigate(`/login?next=${encodeURIComponent(location.pathname + location.search)}`)
      return
    }
    setBusy(true)
    try {
      // Tapping your own stars again takes the rating back
      const taken = rating?.mine === stars
      const result = taken ? await api.removePlaceRating(placeId) : await api.ratePlace(placeId, stars)
      setChanged(result)
      setChosen(result.myReasons)
      setAsking(!taken)
    } catch (e) {
      toast(errorMessage(e, t('Puanın kaydedilemedi', 'Could not save your rating')))
    } finally {
      setBusy(false)
    }
  }

  async function saveReasons() {
    setBusy(true)
    try {
      setChanged(await api.setRatingReasons(placeId, chosen))
      setAsking(false)
      toast(t('Teşekkürler! Cevabın önerileri iyileştirecek.', 'Thanks! Your answer makes suggestions better.'))
    } catch (e) {
      toast(errorMessage(e, t('Cevabın kaydedilemedi', 'Could not save your answer')))
    } finally {
      setBusy(false)
    }
  }

  const toggle = (reason: RatingReason) => setChosen(current => current.includes(reason)
    ? current.filter(r => r !== reason) : current.length < MAX_REASONS ? [...current, reason] : current)

  return (
    <section className="card stack-sm">
      <div className="row" style={{ justifyContent: 'space-between', gap: 8 }}>
        <h2 className="t-headline">{t('Nomi kullanıcılarının puanı', 'Rated by Nomi users')}</h2>
        <span className="t-caption">
          {rating.count > 0 && rating.average != null
            ? t(`${rating.average.toFixed(1)} · ${rating.count} oy`, `${rating.average.toFixed(1)} · ${rating.count} votes`)
            : t('Henüz oy yok', 'No votes yet')}
        </span>
      </div>

      {rating.topReasons.length > 0 && (
        <div className="chips" aria-label={t('Kullanıcıların en çok söyledikleri', 'What users say most')}>
          {rating.topReasons.map(r => (
            <span key={r.reason} className={`badge ${r.positive ? 'badge-success' : 'badge-danger'}`}>
              {r.positive ? <ThumbsUp size={12} /> : <ThumbsDown size={12} />} {reasonLabel(r.reason)} · {r.count}
            </span>
          ))}
        </div>
      )}

      <div className="star-input" role="radiogroup" aria-label={t('Puanın', 'Your rating')}>
        {[1, 2, 3, 4, 5].map(stars => (
          <button key={stars} type="button" role="radio" aria-checked={rating.mine === stars} disabled={busy}
                  aria-label={t(`${stars} yıldız`, `${stars} stars`)}
                  className={rating.mine != null && stars <= rating.mine ? 'on' : ''} onClick={() => void rate(stars)}>
            <Star size={28} />
          </button>
        ))}
      </div>

      {asking && rating.mine != null ? (
        <div className="stack-sm">
          <span className="field-label">
            {rating.mine >= 4 ? t('Neyi beğendin?', 'What did you like?')
              : rating.mine <= 2 ? t('Neyi beğenmedin?', 'What did you not like?')
                : t('Neden bu puanı verdin?', 'Why this rating?')}
            {' '}<span className="t-caption">{t(`(en çok ${MAX_REASONS})`, `(up to ${MAX_REASONS})`)}</span>
          </span>
          <div className="chips">
            {reasonsFor(rating.mine).map(reason => (
              <button key={reason} type="button" className={`chip ${chosen.includes(reason) ? 'active' : ''}`}
                      aria-pressed={chosen.includes(reason)} disabled={busy} onClick={() => toggle(reason)}>
                {reasonLabel(reason)}
              </button>
            ))}
          </div>
          <div className="row" style={{ gap: 8 }}>
            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => void saveReasons()}>
              {busy ? <Spinner size={14} /> : t('Gönder', 'Send')}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={() => setAsking(false)}>
              {t('Geç', 'Skip')}
            </button>
          </div>
        </div>
      ) : (
        <span className="t-caption">
          {rating.mine != null ? (
            <>
              {t(`Sen ${rating.mine} yıldız verdin`, `You gave ${rating.mine} stars`)}
              {rating.myReasons.length > 0 && `: ${rating.myReasons.map(reasonLabel).join(', ')}`}
              {'. '}
              <button type="button" className="link-btn" onClick={() => { setChosen(rating.myReasons); setAsking(true) }}>
                {rating.myReasons.length > 0 ? t('Nedenini değiştir', 'Change why') : t('Nedenini söyle', 'Tell us why')}
              </button>
            </>
          ) : user ? t('Buraya gittin mi? Kaç yıldız verirsin?', 'Been here? How many stars would you give?')
            : t('Puan vermek için giriş yap.', 'Sign in to rate this place.')}
        </span>
      )}
    </section>
  )
}
