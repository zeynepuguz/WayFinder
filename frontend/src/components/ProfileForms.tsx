import { useState, type FormEvent } from 'react'
import { api } from '../api'
import type { WalkingTolerance } from '../api/types'
import { useAuth } from '../context/AuthContext'
import { errorMessage, INTEREST_LABELS, WALKING_LABELS } from '../lib/format'
import { useT } from '../lib/i18n'
import { Alert, Segmented, Spinner, Stepper } from './ui'

// Profile sheets: default planning preferences and suggestions for the app

export function PreferencesForm({ onSaved }: { onSaved: () => void }) {
  const { user, refreshUser } = useAuth()
  const t = useT()
  const prefs = user!.preferences
  const [walking, setWalking] = useState<WalkingTolerance>(prefs.walkingTolerance)
  const [partySize, setPartySize] = useState(prefs.defaultPartySize)
  const [budget, setBudget] = useState(prefs.defaultBudget?.toString() ?? '')
  const [interests, setInterests] = useState<string[]>(prefs.interests)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setSaving(true)
    setError(null)
    try {
      await api.updatePreferences({ walkingTolerance: walking, defaultPartySize: partySize, defaultBudget: budget ? Number(budget) : null, interests })
      await refreshUser()
      onSaved()
    } catch (e) {
      setError(errorMessage(e, t('Kaydedilemedi', 'Could not save')))
    } finally {
      setSaving(false)
    }
  }

  const toggle = (key: string) => setInterests(c => (c.includes(key) ? c.filter(i => i !== key) : [...c, key]))

  return (
    <form className="stack" style={{ gap: 20 }} onSubmit={submit}>
      <p className="t-caption">{t('Başka bir şey söylemediğinde Nomi rotalarını bunlara göre planlar.', 'Unless you say otherwise, Nomi plans your routes with these.')}</p>
      <div className="field">
        <span className="field-label">{t('Yürüme', 'Walking')}</span>
        <Segmented value={walking} onChange={setWalking}
                   options={(Object.keys(WALKING_LABELS) as WalkingTolerance[]).map(w => ({ value: w, label: WALKING_LABELS[w] }))} />
      </div>
      <div className="field-row">
        <div className="field">
          <span className="field-label">{t('Genelde kaç kişi?', 'Usually how many people?')}</span>
          <Stepper value={partySize} min={1} max={20} onChange={setPartySize} label={t('Kişi sayısı', 'Number of people')} />
        </div>
        <label className="field">
          <span className="field-label">{t('Varsayılan bütçe', 'Default budget')}</span>
          <span className="input">
            <input type="number" inputMode="numeric" min={0} step={50} value={budget} placeholder={t('Yok', 'None')} onChange={e => setBudget(e.target.value)} />TL
          </span>
        </label>
      </div>
      <div className="field">
        <span className="field-label">{t('İlgi alanları', 'Interests')}</span>
        <div className="chips">
          {Object.entries(INTEREST_LABELS).map(([key, label]) => (
            <button type="button" key={key} className={`chip ${interests.includes(key) ? 'active' : ''}`}
                    aria-pressed={interests.includes(key)} onClick={() => toggle(key)}>{label}</button>
          ))}
        </div>
      </div>
      {error && <Alert tone="danger"><span>{error}</span></Alert>}
      <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={saving}>{saving ? <Spinner /> : t('Kaydet', 'Save')}</button>
    </form>
  )
}

// Suggestions for the app: reach the owner's admin area; the backend refuses messages with swear words
export function FeedbackForm({ onSent }: { onSent: () => void }) {
  const t = useT()
  const [message, setMessage] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setSending(true)
    setError(null)
    try {
      await api.sendFeedback(message.trim())
      setMessage('')
      onSent()
    } catch (err) {
      setError(errorMessage(err, t('Gönderilemedi', 'Could not send')))
    } finally {
      setSending(false)
    }
  }

  return (
    <form className="stack" onSubmit={submit}>
      <p className="t-caption">{t('Nomi’yi nasıl daha iyi yapabiliriz? Eksik bir mekan, bir hata ya da bir fikir yazabilirsin.', 'How can we make Nomi better? Tell us about a missing place, a bug or an idea.')}</p>
      <textarea className="input" rows={5} maxLength={1000} value={message} onChange={e => setMessage(e.target.value)}
                aria-label={t('Önerin', 'Your suggestion')} style={{ resize: 'vertical', padding: 12 }} />
      {error && <Alert tone="danger">{error}</Alert>}
      <button type="submit" className="btn btn-primary btn-block" disabled={sending || message.trim().length < 3}>
        {sending ? <Spinner /> : t('Gönder', 'Send')}
      </button>
    </form>
  )
}
