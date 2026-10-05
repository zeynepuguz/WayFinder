import {
  ChevronRight, CircleHelp, Crown, FileText, Languages, LogIn, LogOut, MessageSquare, RotateCcw, ShieldCheck,
  SlidersHorizontal, Trash2,
} from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { LanguageSwitch } from '../components/LanguageSwitch'
import { FeedbackForm, PreferencesForm } from '../components/ProfileForms'
import { BackButton, ConfirmSheet, Sheet, useToast } from '../components/ui'
import { MyPhotos } from '../components/UserPhotos'
import { useAuth } from '../context/AuthContext'
import { isNativeApp, restorePurchases } from '../lib/billing'
import { errorMessage, formatDateTime } from '../lib/format'
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
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [feedbackOpen, setFeedbackOpen] = useState(false)

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
        {user.role === 'ADMIN' && (
          <Link className="list-item" to="/admin">
            <span className="list-item-icon"><ShieldCheck size={18} /></span>
            <span className="grow">{t('Yönetim', 'Admin')}</span>
            <ChevronRight size={18} className="muted" />
          </Link>
        )}
        <button className="list-item" onClick={() => setPrefsOpen(true)}>
          <span className="list-item-icon"><SlidersHorizontal size={18} /></span>
          <span className="grow">{t('Tercihlerim', 'My preferences')}</span>
          <ChevronRight size={18} className="muted" />
        </button>
        <LanguageRow />
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
        <button className="list-item list-item-danger" onClick={() => { setDeleteError(null); setDeleteOpen(true) }}>
          <span className="list-item-icon"><Trash2 size={18} /></span>
          <span className="grow">{t('Hesabımı sil', 'Delete my account')}</span>
        </button>
      </div>

      <Sheet open={feedbackOpen} onClose={() => setFeedbackOpen(false)} label={t('Uygulama için önerin var mı?', 'Any suggestions for the app?')}>
        <FeedbackForm onSent={() => { setFeedbackOpen(false); toast(t('Önerin için teşekkürler!', 'Thanks for your suggestion!')) }} />
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
