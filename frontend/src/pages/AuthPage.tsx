import { Eye, EyeOff, KeyRound, Mail, User } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { ApiRequestError } from '../api/client'
import { Alert, BackButton, Segmented, Spinner } from '../components/ui'
import { BrandMark } from '../components/visuals'
import { useAuth } from '../context/AuthContext'
import { LEGAL_LINKS } from '../lib/legal'

export function AuthPage() {
  const { login, register } = useAuth()
  const navigate = useNavigate()
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
          <h1 className="t-display">{mode === 'login' ? 'Tekrar hoş geldin' : 'Hesabını oluştur'}</h1>
          <p className="ink-2">
            {mode === 'login'
              ? 'Rotalarına ve asistanına kaldığın yerden devam et.'
              : 'Bir dakikada kaydol; Nomi gününü senin için planlasın.'}
          </p>
        </div>

        <Segmented
          value={mode}
          onChange={m => { setMode(m); setError(null) }}
          options={[{ value: 'login', label: 'Giriş yap' }, { value: 'register', label: 'Kayıt ol' }]}
        />

        <form className="stack" style={{ gap: 14 }} onSubmit={submit}>
          {mode === 'register' && (
            <label className="field">
              <span className="field-label">Adın</span>
              <span className="input">
                <User size={18} />
                <input value={displayName} onChange={e => setDisplayName(e.target.value)} required maxLength={100}
                       autoComplete="name" placeholder="Adın" />
              </span>
            </label>
          )}
          <label className="field">
            <span className="field-label">E-posta</span>
            <span className="input">
              <Mail size={18} />
              <input type="email" value={email} onChange={e => setEmail(e.target.value)} required autoComplete="email"
                     placeholder="ornek@eposta.com" inputMode="email" />
            </span>
          </label>
          {/* Not wrapped in <label>: the show/hide button must not become part of the input's name */}
          <div className="field">
            <label className="field-label" htmlFor="password">Şifre</label>
            <span className="input">
              <KeyRound size={18} />
              <input id="password" type={showPassword ? 'text' : 'password'} value={password} onChange={e => setPassword(e.target.value)}
                     required minLength={mode === 'register' ? 8 : undefined} placeholder={mode === 'register' ? 'En az 8 karakter' : 'Şifren'}
                     autoComplete={mode === 'login' ? 'current-password' : 'new-password'} />
              <button type="button" className="icon-btn icon-btn-plain" style={{ width: 36, height: 36 }}
                      aria-label={showPassword ? 'Şifreyi gizle' : 'Şifreyi göster'} onClick={() => setShowPassword(v => !v)}>
                {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
              </button>
            </span>
          </div>

          {mode === 'login' && (
            <Link className="t-caption" style={{ alignSelf: 'flex-end', marginTop: -4, fontWeight: 600, color: 'var(--brand-strong)' }}
                  to={`/forgot-password?next=${encodeURIComponent(next)}${email ? `&email=${encodeURIComponent(email)}` : ''}`}>
              Şifremi unuttum
            </Link>
          )}

          {error && <Alert tone="danger"><span>{error}</span></Alert>}

          <button className="btn btn-primary btn-lg btn-block" disabled={busy} style={{ marginTop: 6 }}>
            {busy ? <Spinner /> : mode === 'login' ? 'Giriş yap' : 'Hesap oluştur'}
          </button>
        </form>

        {(LEGAL_LINKS.terms || LEGAL_LINKS.privacy) && (
          <p className="fine-print">
            Devam ederek{' '}
            {LEGAL_LINKS.terms && <a href={LEGAL_LINKS.terms} target="_blank" rel="noreferrer" style={{ textDecoration: 'underline' }}>Kullanım Koşulları</a>}
            {LEGAL_LINKS.terms && LEGAL_LINKS.privacy && ' ve '}
            {LEGAL_LINKS.privacy && <a href={LEGAL_LINKS.privacy} target="_blank" rel="noreferrer" style={{ textDecoration: 'underline' }}>Gizlilik Politikası</a>}
            ’nı kabul etmiş olursun.
          </p>
        )}
      </div>
    </div>
  )
}

function errorText(e: unknown): string {
  if (e instanceof ApiRequestError) {
    if (e.status === 0) return 'Sunucuya ulaşılamıyor. İnternet bağlantını kontrol et.'
    if (e.status === 401) return 'E-posta veya şifre hatalı.'
    if (e.status === 429) return 'Çok fazla deneme yaptın. Bir dakika sonra tekrar dene.'
    if (e.status === 409) return 'Bu e-posta ile zaten bir hesap var.'
    if (e.errors.password) return 'Şifre en az 8 karakter olmalı.'
    if (e.errors.email) return 'Geçerli bir e-posta adresi gir.'
    if (Object.keys(e.errors).length) return 'Lütfen alanları kontrol et.'
    return e.message
  }
  return 'Bir hata oluştu.'
}
