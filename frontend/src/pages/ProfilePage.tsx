import {
  ChevronRight, CircleHelp, Crown, FileText, Languages, LogIn, LogOut, RotateCcw, ShieldCheck, SlidersHorizontal, Trash2,
} from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { api } from '../api'
import type { WalkingTolerance } from '../api/types'
import { LanguageSwitch } from '../components/LanguageSwitch'
import { Alert, BackButton, Segmented, Sheet, Spinner, Stepper, useToast } from '../components/ui'
import { MyPhotos } from '../components/UserPhotos'
import { useAuth } from '../context/AuthContext'
import { isNativeApp, restorePurchases } from '../lib/billing'
import { formatDateTime, INTEREST_LABELS, WALKING_LABELS } from '../lib/format'
import { locale, useT } from '../lib/i18n'
import { LEGAL_LINKS } from '../lib/legal'

export function ProfilePage() {
  const { user, hasAccess, logout, deleteAccount } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const t = useT()
  const [prefsOpen, setPrefsOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  if (!user) {
    return (
      <main className="screen">
        <div className="topbar"><BackButton /><span className="topbar-title">{t('Profil', 'Profile')}</span><span style={{ width: 44 }} /></div>
        <div className="card card-pad-lg stack" style={{ alignItems: 'center', textAlign: 'center' }}>
          <span className="empty-art"><LogIn size={30} /></span>
          <h2 className="t-title">{t('Nomi’ye giriş yap', 'Sign in to Nomi')}</h2>
          <p className="ink-2">{t('Rotalarını kaydet, asistanı kullan ve tercihlerine göre öneriler al.', 'Save your routes, use the assistant and get suggestions that match your preferences.')}</p>
          <Link to="/login?next=/profile" className="btn btn-primary btn-lg btn-block">{t('Giriş yap', 'Sign in')}</Link>
          <Link to="/login?mode=register&next=/profile" className="btn btn-ghost btn-block">{t('Hesap oluştur', 'Create account')}</Link>
        </div>
        <div className="list-group">
          <LanguageRow />
        </div>
        <LegalLinks />
      </main>
    )
  }

  const initials = user.displayName.split(' ').map(p => p[0]).join('').slice(0, 2).toLocaleUpperCase(locale())

  async function confirmDelete() {
    setDeleting(true)
    try {
      await deleteAccount()
      toast(t('Hesabın silindi', 'Your account has been deleted'))
      navigate('/', { replace: true })
    } finally {
      setDeleting(false)
    }
  }

  return (
    <main className="screen">
      <div className="topbar"><BackButton /><span className="topbar-title">{t('Profil', 'Profile')}</span><span style={{ width: 44 }} /></div>

      <div className="row" style={{ gap: 14 }}>
        <span className="bot-avatar" style={{ width: 60, height: 60, borderRadius: 20, fontSize: 22, fontWeight: 800 }}>{initials}</span>
        <div className="grow">
          <h1 className="t-title">{user.displayName}</h1>
          <p className="t-caption">{user.email}</p>
        </div>
      </div>

      {hasAccess ? (
        <div className="status-card">
          <span className="crown"><ShieldCheck size={24} /></span>
          <div className="grow">
            <div className="t-headline">{t('Premium aktif', 'Premium active')}</div>
            <div style={{ fontSize: 13, opacity: 0.75 }}>
              {user.role === 'ADMIN' ? t('Yönetici hesabı', 'Admin account')
                : user.access.free ? t('Süresiz, ücretsiz erişim', 'Free access, no expiry')
                : user.access.expiresAt && t(`${formatDateTime(user.access.expiresAt)} tarihine kadar`, `Until ${formatDateTime(user.access.expiresAt)}`)}
            </div>
          </div>
          {user.role !== 'ADMIN' && !user.access.free && (
            <Link to="/premium" className="btn btn-sm btn-premium">{t('Süre ekle', 'Add time')}</Link>
          )}
        </div>
      ) : (
        <Link to="/premium" className="status-card card-press">
          <span className="crown"><Crown size={24} /></span>
          <div className="grow">
            <div className="t-headline">{t('Nomi Premium’a geç', 'Get Nomi Premium')}</div>
            <div style={{ fontSize: 13, opacity: 0.75 }}>{t('Asistan ve kişisel rotalar · günlük 25 TL’den', 'Assistant and personal routes · from 25 TL a day')}</div>
          </div>
          <ChevronRight size={20} />
        </Link>
      )}

      <div className="list-group">
        <button className="list-item" onClick={() => setPrefsOpen(true)}>
          <span className="list-item-icon"><SlidersHorizontal size={18} /></span>
          <span className="grow">{t('Tercihlerim', 'My preferences')}</span>
          <ChevronRight size={18} className="muted" />
        </button>
        <LanguageRow />
        {isNativeApp() && (
          <button className="list-item" onClick={() => void restorePurchases().then(() => toast(t('Satın alımlar kontrol edildi', 'Purchases checked')), (e: unknown) => toast(e instanceof Error ? e.message : t('Satın alımlar kontrol edilemedi', 'Could not check purchases')))}>
            <span className="list-item-icon"><RotateCcw size={18} /></span>
            <span className="grow">{t('Satın alımları geri yükle', 'Restore purchases')}</span>
          </button>
        )}
        {LEGAL_LINKS.support && (
          <a className="list-item" href={`mailto:${LEGAL_LINKS.support}`}>
            <span className="list-item-icon"><CircleHelp size={18} /></span>
            <span className="grow">{t('Destek', 'Support')}</span>
            <ChevronRight size={18} className="muted" />
          </a>
        )}
      </div>

      <MyPhotos />

      <LegalLinks />

      <div className="list-group">
        <button className="list-item" onClick={logout}>
          <span className="list-item-icon"><LogOut size={18} /></span>
          <span className="grow">{t('Çıkış yap', 'Sign out')}</span>
        </button>
        <button className="list-item list-item-danger" onClick={() => setDeleteOpen(true)}>
          <span className="list-item-icon"><Trash2 size={18} /></span>
          <span className="grow">{t('Hesabımı sil', 'Delete my account')}</span>
        </button>
      </div>

      <Sheet open={prefsOpen} onClose={() => setPrefsOpen(false)} label={t('Tercihlerim', 'My preferences')}>
        <PreferencesForm onSaved={() => { setPrefsOpen(false); toast(t('Tercihlerin kaydedildi', 'Preferences saved')) }} />
      </Sheet>

      <Sheet open={deleteOpen} onClose={() => setDeleteOpen(false)} label={t('Hesabın silinsin mi?', 'Delete your account?')}>
        <div className="stack">
          <p className="ink-2">
            {t('Hesabın, rotaların, kayıtların ve sohbet geçmişin kalıcı olarak silinir. Kalan Premium süren iade edilmez ve geri getirilemez.',
               'Your account, routes, saved items and chat history will be permanently deleted. Any remaining Premium time is not refunded and cannot be restored.')}
          </p>
          <div className="row">
            <button className="btn btn-secondary grow" onClick={() => setDeleteOpen(false)}>{t('Vazgeç', 'Cancel')}</button>
            <button className="btn btn-danger grow" disabled={deleting} onClick={() => void confirmDelete()}>
              {deleting ? <Spinner /> : t('Hesabı sil', 'Delete account')}
            </button>
          </div>
        </div>
      </Sheet>
    </main>
  )
}

function LanguageRow() {
  const t = useT()
  return (
    <div className="list-item">
      <span className="list-item-icon"><Languages size={18} /></span>
      <span className="grow">{t('Dil / Language', 'Language / Dil')}</span>
      <LanguageSwitch />
    </div>
  )
}

function LegalLinks() {
  const t = useT()
  if (!LEGAL_LINKS.privacy && !LEGAL_LINKS.terms) return null
  return (
    <div className="list-group">
      {LEGAL_LINKS.privacy && (
        <a className="list-item" href={LEGAL_LINKS.privacy} target="_blank" rel="noreferrer">
          <span className="list-item-icon"><ShieldCheck size={18} /></span><span className="grow">{t('Gizlilik Politikası', 'Privacy Policy')}</span>
          <ChevronRight size={18} className="muted" />
        </a>
      )}
      {LEGAL_LINKS.terms && (
        <a className="list-item" href={LEGAL_LINKS.terms} target="_blank" rel="noreferrer">
          <span className="list-item-icon"><FileText size={18} /></span><span className="grow">{t('Kullanım Koşulları', 'Terms of Use')}</span>
          <ChevronRight size={18} className="muted" />
        </a>
      )}
    </div>
  )
}

function PreferencesForm({ onSaved }: { onSaved: () => void }) {
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
      setError(e instanceof Error ? e.message : t('Kaydedilemedi', 'Could not save'))
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
      <button className="btn btn-primary btn-lg btn-block" disabled={saving}>{saving ? <Spinner /> : t('Kaydet', 'Save')}</button>
    </form>
  )
}
