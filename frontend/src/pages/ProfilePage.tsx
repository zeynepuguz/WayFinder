import {
  AtSign, Camera, ChevronRight, CircleHelp, Crown, FileText, Info, Languages, LogIn, LogOut, MessageSquare, RotateCcw, ShieldCheck,
  MonitorSmartphone, SlidersHorizontal, SunMoon, Trash2,
} from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api } from '../api'
import { ApiRequestError } from '../api/client'
import { Link, useNavigate } from 'react-router'
import { LanguageSwitch } from '../components/LanguageSwitch'
import { OpenSessions } from '../components/OpenSessions'
import { FeedbackForm, PreferencesForm } from '../components/ProfileForms'
import { Alert, BackButton, ConfirmSheet, Segmented, Sheet, Spinner, useToast } from '../components/ui'
import { MyPhotos } from '../components/UserPhotos'
import { Avatar } from '../components/UserProfileSheet'
import { checkPhotoFile, PHOTO_ACCEPT, uploadErrorMessage } from '../lib/photos'
import { useAuth } from '../context/AuthContext'
import { isNativeApp, restorePurchases } from '../lib/billing'
import { errorMessage, formatDateTime } from '../lib/format'
import { locale, useT } from '../lib/i18n'
import { LEGAL_PATHS, SUPPORT_EMAIL } from '../lib/legal'
import { setThemePref, themePref, type ThemePref } from '../lib/theme'
import { normalizeUsername, USERNAME_TAKEN, usernameProblem } from '../lib/username'

export function ProfilePage() {
  const { user, hasAccess, logout, deleteAccount } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const t = useT()
  const [prefsOpen, setPrefsOpen] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [feedbackOpen, setFeedbackOpen] = useState(false)
  const [sessionsOpen, setSessionsOpen] = useState(false)
  const [usernameOpen, setUsernameOpen] = useState(false)
  const [avatarOpen, setAvatarOpen] = useState(false)

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
          <ThemeRow />
        </div>
        <LegalLinks />
      </main>
    )
  }

  const initials = user.displayName.split(' ').map(p => p[0]).join('').slice(0, 2).toLocaleUpperCase(locale())

  async function confirmDelete() {
    setDeleting(true)
    setDeleteError(null)
    try {
      await deleteAccount()
      toast(t('Hesabın silindi', 'Your account has been deleted'))
      navigate('/', { replace: true })
    } catch (e) {
      setDeleteError(errorMessage(e, t('Hesap silinemedi', 'Couldn’t delete the account')))
    } finally {
      setDeleting(false)
    }
  }

  return (
    <main className="screen">
      <div className="topbar"><BackButton /><span className="topbar-title">{t('Profil', 'Profile')}</span><span style={{ width: 44 }} /></div>

      <div className="row" style={{ gap: 14 }}>
        <button type="button" className="profile-avatar-btn" onClick={() => setAvatarOpen(true)}
                aria-label={user.avatarUrl ? t('Profil fotoğrafını değiştir', 'Change profile photo') : t('Profil fotoğrafı ekle', 'Add a profile photo')}>
          {user.avatarUrl ? <Avatar url={user.avatarUrl} name={user.username} size={60} /> : <span className="profile-avatar">{initials}</span>}
          <span className="cam"><Camera size={13} /></span>
        </button>
        <div className="grow">
          <h1 className="t-title">{user.displayName}</h1>
          {user.username && <p className="profile-username">@{user.username}</p>}
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
        {user.role === 'ADMIN' && (
          <Link className="list-item" to="/admin">
            <span className="list-item-icon"><ShieldCheck size={18} /></span>
            <span className="grow">{t('Yönetim', 'Admin')}</span>
            <ChevronRight size={18} className="muted" />
          </Link>
        )}
        <button className="list-item" onClick={() => setUsernameOpen(true)}>
          <span className="list-item-icon"><AtSign size={18} /></span>
          <span className="grow">{t('Kullanıcı adı', 'Username')}</span>
          <span className="t-caption">@{user.username}</span>
          <ChevronRight size={18} className="muted" />
        </button>
        <button className="list-item" onClick={() => setPrefsOpen(true)}>
          <span className="list-item-icon"><SlidersHorizontal size={18} /></span>
          <span className="grow">{t('Tercihlerim', 'My preferences')}</span>
          <ChevronRight size={18} className="muted" />
        </button>
        <button className="list-item" onClick={() => setSessionsOpen(true)}>
          <span className="list-item-icon"><MonitorSmartphone size={18} /></span>
          <span className="grow">{t('Açık oturumlar', 'Signed-in devices')}</span>
          <ChevronRight size={18} className="muted" />
        </button>
        <LanguageRow />
        <ThemeRow />
        {/* Suggestions are stored with the sender, so only signed-in users see this row */}
        <button className="list-item" onClick={() => setFeedbackOpen(true)}>
          <span className="list-item-icon"><MessageSquare size={18} /></span>
          <span className="grow">{t('Uygulama için önerin var mı?', 'Any suggestions for the app?')}</span>
          <ChevronRight size={18} className="muted" />
        </button>
        {isNativeApp() && (
          <button className="list-item" onClick={() => void restorePurchases().then(() => toast(t('Satın alımlar kontrol edildi', 'Purchases checked')), (e: unknown) => toast(errorMessage(e, t('Satın alımlar kontrol edilemedi', 'Could not check purchases'))))}>
            <span className="list-item-icon"><RotateCcw size={18} /></span>
            <span className="grow">{t('Satın alımları geri yükle', 'Restore purchases')}</span>
          </button>
        )}
        {SUPPORT_EMAIL && (
          <a className="list-item" href={`mailto:${SUPPORT_EMAIL}`}>
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
        <button className="list-item list-item-danger" onClick={() => { setDeleteError(null); setDeleteOpen(true) }}>
          <span className="list-item-icon"><Trash2 size={18} /></span>
          <span className="grow">{t('Hesabımı sil', 'Delete my account')}</span>
        </button>
      </div>

      <Sheet open={feedbackOpen} onClose={() => setFeedbackOpen(false)} label={t('Uygulama için önerin var mı?', 'Any suggestions for the app?')}>
        <FeedbackForm onSent={() => { setFeedbackOpen(false); toast(t('Önerin için teşekkürler!', 'Thanks for your suggestion!')) }} />
      </Sheet>

      <Sheet open={sessionsOpen} onClose={() => setSessionsOpen(false)} label={t('Açık oturumlar', 'Signed-in devices')}>
        {sessionsOpen && <OpenSessions />}
      </Sheet>

      <Sheet open={avatarOpen} onClose={() => setAvatarOpen(false)} label={t('Profil fotoğrafı', 'Profile photo')}>
        {avatarOpen && <AvatarForm onDone={message => { setAvatarOpen(false); toast(message) }} />}
      </Sheet>

      <Sheet open={usernameOpen} onClose={() => setUsernameOpen(false)} label={t('Kullanıcı adı', 'Username')}>
        {usernameOpen && <UsernameForm current={user.username} onSaved={() => { setUsernameOpen(false); toast(t('Kullanıcı adın değişti', 'Your username has changed')) }} />}
      </Sheet>

      <Sheet open={prefsOpen} onClose={() => setPrefsOpen(false)} label={t('Tercihlerim', 'My preferences')}>
        <PreferencesForm onSaved={() => { setPrefsOpen(false); toast(t('Tercihlerin kaydedildi', 'Preferences saved')) }} />
      </Sheet>

      <ConfirmSheet open={deleteOpen} onClose={() => setDeleteOpen(false)} label={t('Hesabın silinsin mi?', 'Delete your account?')}
                    text={t('Hesabın, rotaların, kayıtların ve sohbet geçmişin kalıcı olarak silinir. Kalan Premium süren iade edilmez ve geri getirilemez.',
                            'Your account, routes, saved items and chat history will be permanently deleted. Any remaining Premium time is not refunded and cannot be restored.')}
                    confirmLabel={t('Hesabı sil', 'Delete account')} busy={deleting} error={deleteError} onConfirm={() => void confirmDelete()} />
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
  return (
    <div className="list-group">
      <Link className="list-item" to={LEGAL_PATHS.about}>
        <span className="list-item-icon"><Info size={18} /></span><span className="grow">{t('Hakkımızda ve İletişim', 'About & Contact')}</span>
        <ChevronRight size={18} className="muted" />
      </Link>
      <Link className="list-item" to={LEGAL_PATHS.privacy}>
        <span className="list-item-icon"><ShieldCheck size={18} /></span><span className="grow">{t('Gizlilik Politikası', 'Privacy Policy')}</span>
        <ChevronRight size={18} className="muted" />
      </Link>
      <Link className="list-item" to={LEGAL_PATHS.terms}>
        <span className="list-item-icon"><FileText size={18} /></span><span className="grow">{t('Kullanım Koşulları', 'Terms of Use')}</span>
        <ChevronRight size={18} className="muted" />
      </Link>
    </div>
  )
}

// Light or dark look: the phone's setting by default (lib/theme)
function ThemeRow() {
  const t = useT()
  const [pref, setPref] = useState<ThemePref>(themePref)
  return (
    <div className="list-item">
      <span className="list-item-icon"><SunMoon size={18} /></span>
      <span className="grow">{t('Tema', 'Theme')}</span>
      <div className="theme-switch">
        <Segmented<ThemePref> value={pref} onChange={p => { setPref(p); setThemePref(p) }} options={[
          { value: 'system', label: t('Sistem', 'System') },
          { value: 'light', label: t('Açık', 'Light') },
          { value: 'dark', label: t('Koyu', 'Dark') },
        ]} />
      </div>
    </div>
  )
}

// A new public username; the rules are checked here, whether it is free by the server
function UsernameForm({ current, onSaved }: { current: string; onSaved: () => void }) {
  const t = useT()
  const { refreshUser } = useAuth()
  const [value, setValue] = useState(current)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save(event: FormEvent) {
    event.preventDefault()
    const problem = usernameProblem(value)
    if (problem) {
      setError(problem)
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.changeUsername(normalizeUsername(value))
      await refreshUser()
      onSaved()
    } catch (e) {
      setError(e instanceof ApiRequestError && e.status === 409 ? USERNAME_TAKEN()
        : errorMessage(e, t('Kullanıcı adı değiştirilemedi', 'Couldn’t change the username')))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="stack" onSubmit={e => void save(e)}>
      <p className="t-caption">{t('Eklediğin fotoğrafların altında bu ad görünür; gerçek adın görünmez.', 'This name is shown under the photos you add; your real name is never shown.')}</p>
      <span className="input">
        <AtSign size={18} />
        <input value={value} onChange={e => setValue(e.target.value)} maxLength={20} autoFocus autoCapitalize="none" spellCheck={false}
               aria-label={t('Kullanıcı adı', 'Username')} />
      </span>
      <span className="t-caption">{t('3-20 karakter: harf, rakam, _ ve .', '3-20 characters: letters, digits, _ and .')}</span>
      {error && <Alert tone="danger"><span>{error}</span></Alert>}
      <button type="submit" className="btn btn-primary btn-block" disabled={busy || normalizeUsername(value) === current}>
        {busy ? <Spinner /> : t('Kaydet', 'Save')}
      </button>
    </form>
  )
}

// Optional profile photo: other users see it with the username under the photos this user adds
function AvatarForm({ onDone }: { onDone: (message: string) => void }) {
  const t = useT()
  const { user, refreshUser } = useAuth()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  if (!user) return null

  async function run(action: () => Promise<unknown>, done: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      await refreshUser()
      onDone(done)
    } catch (e) {
      setError(uploadErrorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  function choose(file: File | undefined) {
    if (!file) return
    const problem = checkPhotoFile(file)
    if (problem) {
      setError(problem)
      return
    }
    void run(() => api.setAvatar(file), t('Profil fotoğrafın güncellendi', 'Your profile photo is updated'))
  }

  return (
    <div className="stack" style={{ alignItems: 'center', textAlign: 'center' }}>
      <Avatar url={user.avatarUrl} name={user.username} size={96} />
      <p className="t-caption">{t('Eklediğin fotoğraflarda kullanıcı adınla birlikte görünür. Eklemek isteğe bağlı.', 'Shown with your username on the photos you add. Adding one is optional.')}</p>
      {error && <Alert tone="danger"><span>{error}</span></Alert>}
      <label className={`btn btn-primary btn-block ${busy ? 'disabled' : ''}`} aria-disabled={busy}>
        {busy ? <Spinner /> : <><Camera size={18} /> {user.avatarUrl ? t('Başka fotoğraf seç', 'Choose another photo') : t('Fotoğraf seç', 'Choose a photo')}</>}
        <input type="file" accept={PHOTO_ACCEPT} hidden disabled={busy} onChange={e => { choose(e.target.files?.[0]); e.target.value = '' }} />
      </label>
      {user.avatarUrl && (
        <button type="button" className="btn btn-ghost btn-block" style={{ color: 'var(--danger)' }} disabled={busy}
                onClick={() => void run(() => api.removeAvatar(), t('Profil fotoğrafın kaldırıldı', 'Your profile photo is removed'))}>
          {t('Fotoğrafı kaldır', 'Remove photo')}
        </button>
      )}
    </div>
  )
}
