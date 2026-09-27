import {
  ChevronRight, CircleHelp, Crown, FileText, LogIn, LogOut, RotateCcw, ShieldCheck, SlidersHorizontal, Trash2,
} from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { api } from '../api'
import type { WalkingTolerance } from '../api/types'
import { Alert, BackButton, Segmented, Sheet, Spinner, Stepper, useToast } from '../components/ui'
import { useAuth } from '../context/AuthContext'
import { isNativeApp, restorePurchases } from '../lib/billing'
import { formatDateTime, INTEREST_LABELS, WALKING_LABELS } from '../lib/format'
import { LEGAL_LINKS } from '../lib/legal'

export function ProfilePage() {
  const { user, hasAccess, logout, deleteAccount } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [prefsOpen, setPrefsOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  if (!user) {
    return (
      <main className="screen">
        <div className="topbar"><BackButton /><span className="topbar-title">Profil</span><span style={{ width: 44 }} /></div>
        <div className="card card-pad-lg stack" style={{ alignItems: 'center', textAlign: 'center' }}>
          <span className="empty-art"><LogIn size={30} /></span>
          <h2 className="t-title">Nomi’ye giriş yap</h2>
          <p className="ink-2">Rotalarını kaydet, asistanı kullan ve tercihlerine göre öneriler al.</p>
          <Link to="/login?next=/profile" className="btn btn-primary btn-lg btn-block">Giriş yap</Link>
          <Link to="/login?mode=register&next=/profile" className="btn btn-ghost btn-block">Hesap oluştur</Link>
        </div>
        <LegalLinks />
      </main>
    )
  }

  const initials = user.displayName.split(' ').map(p => p[0]).join('').slice(0, 2).toLocaleUpperCase('tr')

  async function confirmDelete() {
    setDeleting(true)
    try {
      await deleteAccount()
      toast('Hesabın silindi')
      navigate('/', { replace: true })
    } finally {
      setDeleting(false)
    }
  }

  return (
    <main className="screen">
      <div className="topbar"><BackButton /><span className="topbar-title">Profil</span><span style={{ width: 44 }} /></div>

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
            <div className="t-headline">Premium aktif</div>
            <div style={{ fontSize: 13, opacity: 0.75 }}>
              {user.role === 'ADMIN' ? 'Yönetici hesabı'
                : user.access.free ? 'Süresiz, ücretsiz erişim'
                : user.access.expiresAt && `${formatDateTime(user.access.expiresAt)} tarihine kadar`}
            </div>
          </div>
          {user.role !== 'ADMIN' && !user.access.free && (
            <Link to="/premium" className="btn btn-sm btn-premium">Süre ekle</Link>
          )}
        </div>
      ) : (
        <Link to="/premium" className="status-card card-press">
          <span className="crown"><Crown size={24} /></span>
          <div className="grow">
            <div className="t-headline">Nomi Premium’a geç</div>
            <div style={{ fontSize: 13, opacity: 0.75 }}>Asistan ve kişisel rotalar · günlük 25 TL’den</div>
          </div>
          <ChevronRight size={20} />
        </Link>
      )}

      <div className="list-group">
        <button className="list-item" onClick={() => setPrefsOpen(true)}>
          <span className="list-item-icon"><SlidersHorizontal size={18} /></span>
          <span className="grow">Tercihlerim</span>
          <ChevronRight size={18} className="muted" />
        </button>
        {isNativeApp() && (
          <button className="list-item" onClick={() => void restorePurchases().then(() => toast('Satın alımlar kontrol edildi'))}>
            <span className="list-item-icon"><RotateCcw size={18} /></span>
            <span className="grow">Satın alımları geri yükle</span>
          </button>
        )}
        {LEGAL_LINKS.support && (
          <a className="list-item" href={`mailto:${LEGAL_LINKS.support}`}>
            <span className="list-item-icon"><CircleHelp size={18} /></span>
            <span className="grow">Destek</span>
            <ChevronRight size={18} className="muted" />
          </a>
        )}
      </div>

      <LegalLinks />

      <div className="list-group">
        <button className="list-item" onClick={logout}>
          <span className="list-item-icon"><LogOut size={18} /></span>
          <span className="grow">Çıkış yap</span>
        </button>
        <button className="list-item list-item-danger" onClick={() => setDeleteOpen(true)}>
          <span className="list-item-icon"><Trash2 size={18} /></span>
          <span className="grow">Hesabımı sil</span>
        </button>
      </div>

      <Sheet open={prefsOpen} onClose={() => setPrefsOpen(false)} label="Tercihlerim">
        <PreferencesForm onSaved={() => { setPrefsOpen(false); toast('Tercihlerin kaydedildi') }} />
      </Sheet>

      <Sheet open={deleteOpen} onClose={() => setDeleteOpen(false)} label="Hesabın silinsin mi?">
        <div className="stack">
          <p className="ink-2">
            Hesabın, rotaların, kayıtların ve sohbet geçmişin kalıcı olarak silinir. Kalan Premium süren iade edilmez ve geri getirilemez.
          </p>
          <div className="row">
            <button className="btn btn-secondary grow" onClick={() => setDeleteOpen(false)}>Vazgeç</button>
            <button className="btn btn-danger grow" disabled={deleting} onClick={() => void confirmDelete()}>
              {deleting ? <Spinner /> : 'Hesabı sil'}
            </button>
          </div>
        </div>
      </Sheet>
    </main>
  )
}

function LegalLinks() {
  if (!LEGAL_LINKS.privacy && !LEGAL_LINKS.terms) return null
  return (
    <div className="list-group">
      {LEGAL_LINKS.privacy && (
        <a className="list-item" href={LEGAL_LINKS.privacy} target="_blank" rel="noreferrer">
          <span className="list-item-icon"><ShieldCheck size={18} /></span><span className="grow">Gizlilik Politikası</span>
          <ChevronRight size={18} className="muted" />
        </a>
      )}
      {LEGAL_LINKS.terms && (
        <a className="list-item" href={LEGAL_LINKS.terms} target="_blank" rel="noreferrer">
          <span className="list-item-icon"><FileText size={18} /></span><span className="grow">Kullanım Koşulları</span>
          <ChevronRight size={18} className="muted" />
        </a>
      )}
    </div>
  )
}

function PreferencesForm({ onSaved }: { onSaved: () => void }) {
  const { user, refreshUser } = useAuth()
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
      setError(e instanceof Error ? e.message : 'Kaydedilemedi')
    } finally {
      setSaving(false)
    }
  }

  const toggle = (key: string) => setInterests(c => (c.includes(key) ? c.filter(i => i !== key) : [...c, key]))

  return (
    <form className="stack" style={{ gap: 20 }} onSubmit={submit}>
      <p className="t-caption">Başka bir şey söylemediğinde Nomi rotalarını bunlara göre planlar.</p>
      <div className="field">
        <span className="field-label">Yürüme</span>
        <Segmented value={walking} onChange={setWalking}
                   options={(Object.keys(WALKING_LABELS) as WalkingTolerance[]).map(w => ({ value: w, label: WALKING_LABELS[w] }))} />
      </div>
      <div className="field-row">
        <div className="field">
          <span className="field-label">Genelde kaç kişi?</span>
          <Stepper value={partySize} min={1} max={20} onChange={setPartySize} label="Kişi sayısı" />
        </div>
        <label className="field">
          <span className="field-label">Varsayılan bütçe</span>
          <span className="input">
            <input type="number" inputMode="numeric" min={0} step={50} value={budget} placeholder="Yok" onChange={e => setBudget(e.target.value)} />TL
          </span>
        </label>
      </div>
      <div className="field">
        <span className="field-label">İlgi alanları</span>
        <div className="chips">
          {Object.entries(INTEREST_LABELS).map(([key, label]) => (
            <button type="button" key={key} className={`chip ${interests.includes(key) ? 'active' : ''}`}
                    aria-pressed={interests.includes(key)} onClick={() => toggle(key)}>{label}</button>
          ))}
        </div>
      </div>
      {error && <Alert tone="danger"><span>{error}</span></Alert>}
      <button className="btn btn-primary btn-lg btn-block" disabled={saving}>{saving ? <Spinner /> : 'Kaydet'}</button>
    </form>
  )
}
