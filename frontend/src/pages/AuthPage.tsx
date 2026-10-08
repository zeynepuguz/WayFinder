import { Eye, EyeOff, Hash, KeyRound, Mail, User } from 'lucide-react'
import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { api } from '../api'
import { ApiRequestError } from '../api/client'
import type { CodeSent } from '../api/types'
import { Alert, BackButton, Segmented, Spinner } from '../components/ui'
import { BrandMark } from '../components/visuals'
import { useAuth } from '../context/AuthContext'
import { LEGAL_PATHS } from '../lib/legal'
import { safeNext } from '../lib/nav'
import { tr, useT } from '../lib/i18n'

type Mode = 'login' | 'register'

// Step 1: the form (sign-in: e-mail + password; sign-up: name, surname, e-mail, password) → a 6-digit code is
// e-mailed. Step 2: the code signs in. So a password alone never opens an account.
export function AuthPage() {
  const { login, register, verifyCode } = useAuth()
  const navigate = useNavigate()
  const t = useT()
  const [params] = useSearchParams()
  const next = safeNext(params.get('next')) ?? '/'

  const [mode, setMode] = useState<Mode>(params.get('mode') === 'register' ? 'register' : 'login')
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [sent, setSent] = useState<CodeSent | null>(null)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submitForm(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      setSent(mode === 'login'
        ? await login(email, password)
        : await register({ firstName, lastName, email, password }))
      setCode('')
    } catch (e) {
      setError(errorText(e, mode))
    } finally {
      setBusy(false)
    }
  }

  async function submitCode(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await verifyCode(mode === 'login' ? 'SIGN_IN' : 'SIGN_UP', email, code)
      navigate(next, { replace: true })
    } catch (e) {
      setError(codeErrorText(e))
    } finally {
      setBusy(false)
    }
  }

  if (sent) {
    return (
      <AuthShell>
        <div className="auth-head">
          <h1 className="t-display">{t('E-postanı kontrol et', 'Check your email')}</h1>
          <p className="ink-2">
            {t(`${sent.email} adresine 6 haneli bir kod gönderdik. Gelen kutunu ve spam klasörünü kontrol et; kod ${sent.validMinutes} dakika geçerli.`,
              `We sent a 6-digit code to ${sent.email}. Check your inbox and spam folder; the code is valid for ${sent.validMinutes} minutes.`)}
          </p>
        </div>
        <form className="stack" style={{ gap: 14 }} onSubmit={submitCode}>
          <label className="field">
            <span className="field-label">{t('Doğrulama kodu', 'Verification code')}</span>
            <span className="input">
              <Hash size={18} />
              <input value={code} onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} required autoFocus
                     pattern="\d{6}" inputMode="numeric" autoComplete="one-time-code" placeholder={t('6 haneli kod', '6-digit code')} />
            </span>
          </label>

          {error && <Alert tone="danger"><span>{error}</span></Alert>}

          <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={busy || code.length !== 6}>
            {busy ? <Spinner /> : t('Doğrula', 'Verify')}
          </button>
          <ResendButton email={email} purpose={mode === 'login' ? 'SIGN_IN' : 'SIGN_UP'} waitSeconds={sent.resendAfterSeconds} />
          <button type="button" className="btn btn-ghost btn-block" onClick={() => { setSent(null); setError(null) }}>
            {t('E-postayı değiştir', 'Use another email')}
          </button>
        </form>
      </AuthShell>
    )
  }

  return (
    <AuthShell>
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

      <form className="stack" style={{ gap: 14 }} onSubmit={submitForm}>
        {mode === 'register' && (
          <div className="row" style={{ gap: 10, alignItems: 'flex-start' }}>
            <label className="field grow">
              <span className="field-label">{t('Ad', 'First name')}</span>
              <span className="input">
                <User size={18} />
                <input value={firstName} onChange={e => setFirstName(e.target.value)} required maxLength={50}
                       autoComplete="given-name" placeholder={t('Adın', 'First name')} style={{ minWidth: 0 }} />
              </span>
            </label>
            <label className="field grow">
              <span className="field-label">{t('Soyad', 'Last name')}</span>
              <span className="input">
                <input value={lastName} onChange={e => setLastName(e.target.value)} required maxLength={50}
                       autoComplete="family-name" placeholder={t('Soyadın', 'Last name')} style={{ minWidth: 0 }} />
              </span>
            </label>
          </div>
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

        <button type="submit" className="btn btn-primary btn-lg btn-block" disabled={busy} style={{ marginTop: 6 }}>
          {busy ? <Spinner /> : mode === 'login' ? t('Giriş yap', 'Log in') : t('Hesap oluştur', 'Create account')}
        </button>
      </form>

      <p className="fine-print">
        {t('Devam ederek', 'By continuing you accept the')}{' '}
        <Link to={LEGAL_PATHS.terms} style={{ textDecoration: 'underline' }}>{t('Kullanım Koşulları', 'Terms of Use')}</Link>
        {t(' ve ', ' and ')}
        <Link to={LEGAL_PATHS.privacy} style={{ textDecoration: 'underline' }}>{t('Gizlilik Politikası', 'Privacy Policy')}</Link>
        {t('’nı kabul etmiş olursun.', '.')}
      </p>
    </AuthShell>
  )
}

function AuthShell({ children }: { children: ReactNode }) {
  return (
    <div className="app">
      <div className="screen screen-no-tabbar auth-screen">
        <div className="topbar">
          <BackButton to="/" />
          <BrandMark />
          <span style={{ width: 44 }} />
        </div>
        {children}
      </div>
    </div>
  )
}

// A new code once the wait is over (the backend sends at most one a minute)
function ResendButton({ email, purpose, waitSeconds }: { email: string; purpose: 'SIGN_UP' | 'SIGN_IN'; waitSeconds: number }) {
  const t = useT()
  const [left, setLeft] = useState(waitSeconds)
  const [note, setNote] = useState<string | null>(null)

  useEffect(() => {
    if (left <= 0) return
    const timer = setTimeout(() => setLeft(s => s - 1), 1000)
    return () => clearTimeout(timer)
  }, [left])

  async function resend() {
    setNote(null)
    try {
      await api.resendCode(email, purpose)
      setNote(t('Yeni kod gönderildi.', 'A new code is on its way.'))
      setLeft(waitSeconds)
    } catch (e) {
      setNote(codeErrorText(e))
    }
  }

  return (
    <>
      <button type="button" className="btn btn-secondary btn-block" disabled={left > 0} onClick={() => void resend()}>
        {left > 0 ? t(`Kodu tekrar gönder (${left} sn)`, `Resend code (${left} s)`) : t('Kodu tekrar gönder', 'Resend code')}
      </button>
      {note && <span className="t-caption" role="status" style={{ textAlign: 'center' }}>{note}</span>}
    </>
  )
}

function errorText(e: unknown, mode: Mode): string {
  if (e instanceof ApiRequestError) {
    if (e.status === 0) return tr('Sunucuya ulaşılamıyor. İnternet bağlantını kontrol et.', 'Can’t reach the server. Check your internet connection.')
    if (e.status === 401) return tr('E-posta veya şifre hatalı.', 'Wrong email or password.')
    if (e.status === 429) return mode === 'login'
      ? tr('Çok fazla hatalı deneme yapıldı. 15 dakika sonra tekrar dene.', 'Too many wrong attempts. Try again in 15 minutes.')
      : tr('Çok fazla deneme yaptın. Bir dakika sonra tekrar dene.', 'Too many attempts. Try again in a minute.')
    if (e.status === 409) return tr('Bu e-posta ile zaten bir hesap var. Giriş yapabilirsin.', 'An account with this email already exists. You can log in.')
    if (e.errors.firstName || e.errors.lastName) return tr('Adını ve soyadını harflerle yaz.', 'Write your first and last name with letters.')
    if (e.errors.password) return tr('Şifre en az 8 karakter olmalı.', 'Password must be at least 8 characters.')
    if (e.errors.email) return tr('Geçerli bir e-posta adresi gir.', 'Enter a valid email address.')
    if (Object.keys(e.errors).length) return tr('Lütfen alanları kontrol et.', 'Please check the fields.')
    return e.message
  }
  return tr('Bir hata oluştu.', 'Something went wrong.')
}

function codeErrorText(e: unknown): string {
  if (e instanceof ApiRequestError) {
    if (e.status === 0) return tr('Sunucuya ulaşılamıyor. İnternet bağlantını kontrol et.', 'Can’t reach the server. Check your internet connection.')
    if (e.status === 400) return tr('Kod hatalı ya da süresi dolmuş. 5 yanlış denemeden sonra yeni kod iste.', 'Wrong or expired code. After 5 wrong tries, ask for a new one.')
    if (e.status === 429) return tr('Çok fazla deneme yaptın. Bir dakika sonra tekrar dene.', 'Too many attempts. Try again in a minute.')
    return e.message
  }
  return tr('Bir hata oluştu.', 'Something went wrong.')
}
