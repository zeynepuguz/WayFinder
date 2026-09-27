import { Eye, EyeOff, KeyRound, Mail, User } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { ApiRequestError } from '../api/client'
import { Alert, BackButton, Segmented, Spinner } from '../components/ui'
import { BrandMark } from '../components/visuals'
import { useAuth } from '../context/AuthContext'
import { LEGAL_LINKS } from '../lib/legal'
import { tr, useT } from '../lib/i18n'

export function AuthPage() {
  const { login, register } = useAuth()
  const navigate = useNavigate()
  const t = useT()
  const [params] = useSearchParams()
  const next = params.get('next') ?? '/'

  const [mode, setMode] = useState<'login' | 'register'>(params.get('mode') === 'register' ? 'register' : 'login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (mode === 'login') await login(email, password)
      else await register(email, password, displayName)
      navigate(next, { replace: true })
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="app">
      <div className="screen screen-no-tabbar auth-screen">
        <div className="topbar">
          <BackButton to="/" />
          <BrandMark />
          <span style={{ width: 44 }} />
        </div>

        <div className="auth-head">
          <h1 className="t-display">{mode === 'login' ? t('Tekrar hoş geldin', 'Welcome back') : t('Hesabını oluştur', 'Create your account')}</h1>
          <p className="ink-2">
            {mode === 'login'
              ? t('Rotalarına ve asistanına kaldığın yerden devam et.', 'Pick up your routes and assistant where you left off.')
              : t('Bir dakikada kaydol; Nomi gününü senin için planlasın.', 'Sign up in a minute and let Nomi plan your day.')}
          </p>
        </div>

        <Segmented
          value={mode}
          onChange={m => { setMode(m); setError(null) }}
          options={[{ value: 'login', label: t('Giriş yap', 'Log in') }, { value: 'register', label: t('Kayıt ol', 'Sign up') }]}
        />

        <form className="stack" style={{ gap: 14 }} onSubmit={submit}>
          {mode === 'register' && (
            <label className="field">
              <span className="field-label">{t('Adın', 'Your name')}</span>
              <span className="input">
                <User size={18} />
                <input value={displayName} onChange={e => setDisplayName(e.target.value)} required maxLength={100}
                       autoComplete="name" placeholder={t('Adın', 'Your name')} />
              </span>
            </label>
          )}
          <label className="field">
            <span className="field-label">{t('E-posta', 'Email')}</span>
            <span className="input">
              <Mail size={18} />
              <input type="email" value={email} onChange={e => setEmail(e.target.value)} required autoComplete="email"
                     placeholder={t('ornek@eposta.com', 'name@example.com')} inputMode="email" />
            </span>
          </label>
          {/* Not wrapped in <label>: the show/hide button must not become part of the input's name */}
          <div className="field">
            <label className="field-label" htmlFor="password">{t('Şifre', 'Password')}</label>
            <span className="input">
              <KeyRound size={18} />
              <input id="password" type={showPassword ? 'text' : 'password'} value={password} onChange={e => setPassword(e.target.value)}
                     required minLength={mode === 'register' ? 8 : undefined} placeholder={mode === 'register' ? t('En az 8 karakter', 'At least 8 characters') : t('Şifren', 'Your password')}
                     autoComplete={mode === 'login' ? 'current-password' : 'new-password'} />
              <button type="button" className="icon-btn icon-btn-plain" style={{ width: 36, height: 36 }}
                      aria-label={showPassword ? t('Şifreyi gizle', 'Hide password') : t('Şifreyi göster', 'Show password')} onClick={() => setShowPassword(v => !v)}>
                {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
              </button>
            </span>
          </div>

          {mode === 'login' && (
            <Link className="t-caption" style={{ alignSelf: 'flex-end', marginTop: -4, fontWeight: 600, color: 'var(--brand-strong)' }}
                  to={`/forgot-password?next=${encodeURIComponent(next)}${email ? `&email=${encodeURIComponent(email)}` : ''}`}>
              {t('Şifremi unuttum', 'Forgot password?')}
            </Link>
          )}

          {error && <Alert tone="danger"><span>{error}</span></Alert>}

          <button className="btn btn-primary btn-lg btn-block" disabled={busy} style={{ marginTop: 6 }}>
            {busy ? <Spinner /> : mode === 'login' ? t('Giriş yap', 'Log in') : t('Hesap oluştur', 'Create account')}
          </button>
        </form>

        {(LEGAL_LINKS.terms || LEGAL_LINKS.privacy) && (
          <p className="fine-print">
            {t('Devam ederek', 'By continuing you accept the')}{' '}
            {LEGAL_LINKS.terms && <a href={LEGAL_LINKS.terms} target="_blank" rel="noreferrer" style={{ textDecoration: 'underline' }}>{t('Kullanım Koşulları', 'Terms of Use')}</a>}
            {LEGAL_LINKS.terms && LEGAL_LINKS.privacy && t(' ve ', ' and ')}
            {LEGAL_LINKS.privacy && <a href={LEGAL_LINKS.privacy} target="_blank" rel="noreferrer" style={{ textDecoration: 'underline' }}>{t('Gizlilik Politikası', 'Privacy Policy')}</a>}
            {t('’nı kabul etmiş olursun.', '.')}
          </p>
        )}
      </div>
    </div>
  )
}

function errorText(e: unknown): string {
  if (e instanceof ApiRequestError) {
    if (e.status === 0) return tr('Sunucuya ulaşılamıyor. İnternet bağlantını kontrol et.', 'Can’t reach the server. Check your internet connection.')
    if (e.status === 401) return tr('E-posta veya şifre hatalı.', 'Wrong email or password.')
    if (e.status === 429) return tr('Çok fazla deneme yaptın. Bir dakika sonra tekrar dene.', 'Too many attempts. Try again in a minute.')
    if (e.status === 409) return tr('Bu e-posta ile zaten bir hesap var.', 'An account with this email already exists.')
    if (e.errors.password) return tr('Şifre en az 8 karakter olmalı.', 'Password must be at least 8 characters.')
    if (e.errors.email) return tr('Geçerli bir e-posta adresi gir.', 'Enter a valid email address.')
    if (Object.keys(e.errors).length) return tr('Lütfen alanları kontrol et.', 'Please check the fields.')
    return e.message
  }
  return tr('Bir hata oluştu.', 'Something went wrong.')
}
