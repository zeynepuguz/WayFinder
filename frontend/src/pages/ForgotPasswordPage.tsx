import { Eye, EyeOff, Hash, KeyRound, Mail } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { api } from '../api'
import { ApiRequestError } from '../api/client'
import { Alert, BackButton, Spinner, useToast } from '../components/ui'
import { BrandMark } from '../components/visuals'
import { useAuth } from '../context/AuthContext'

const RESEND_SECONDS = 60

// Step 1: e-mail → a 6-digit code is sent. Step 2: code + new password → signed in.
export function ForgotPasswordPage() {
  const { resetPassword } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [params] = useSearchParams()
  const next = params.get('next') ?? '/'

  const [step, setStep] = useState<'email' | 'code'>('email')
  const [email, setEmail] = useState(params.get('email') ?? '')
  const [code, setCode] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [resendIn, setResendIn] = useState(0)

  useEffect(() => {
    if (resendIn <= 0) return
    const timer = setTimeout(() => setResendIn(s => s - 1), 1000)
    return () => clearTimeout(timer)
  }, [resendIn])

  async function sendCode(event?: FormEvent) {
    event?.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.forgotPassword(email)
      setStep('code')
      setResendIn(RESEND_SECONDS)
    } catch (e) {
      setError(errorText(e))
    } finally {
      setBusy(false)
    }
  }

  async function submitNewPassword(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await resetPassword(email, code, password)
      toast('Şifren yenilendi')
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
          <BackButton />
          <BrandMark />
          <span style={{ width: 44 }} />
        </div>

        <div className="auth-head">
          <h1 className="t-display">{step === 'email' ? 'Şifreni mi unuttun?' : 'Kodu gir'}</h1>
          <p className="ink-2">
            {step === 'email'
              ? 'E-posta adresini yaz, sana 6 haneli bir kod gönderelim.'
              : `${email} adresine bir kod gönderdik. Gelen kutunu ve spam klasörünü kontrol et; kod 15 dakika geçerli.`}
          </p>
        </div>

        {step === 'email' ? (
          <form className="stack" style={{ gap: 14 }} onSubmit={sendCode}>
            <label className="field">
              <span className="field-label">E-posta</span>
              <span className="input">
                <Mail size={18} />
                <input type="email" value={email} onChange={e => setEmail(e.target.value)} required autoComplete="email"
                       placeholder="ornek@eposta.com" inputMode="email" />
              </span>
            </label>

            {error && <Alert tone="danger"><span>{error}</span></Alert>}

            <button className="btn btn-primary btn-lg btn-block" disabled={busy} style={{ marginTop: 6 }}>
              {busy ? <Spinner /> : 'Kod gönder'}
            </button>
          </form>
        ) : (
          <form className="stack" style={{ gap: 14 }} onSubmit={submitNewPassword}>
            <label className="field">
              <span className="field-label">Doğrulama kodu</span>
              <span className="input">
                <Hash size={18} />
                <input value={code} onChange={e => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} required
                       pattern="\d{6}" inputMode="numeric" autoComplete="one-time-code" placeholder="6 haneli kod" />
              </span>
            </label>
            {/* Not wrapped in <label>: the show/hide button must not become part of the input's name */}
            <div className="field">
              <label className="field-label" htmlFor="new-password">Yeni şifre</label>
              <span className="input">
                <KeyRound size={18} />
                <input id="new-password" type={showPassword ? 'text' : 'password'} value={password}
                       onChange={e => setPassword(e.target.value)} required minLength={8}
                       placeholder="En az 8 karakter" autoComplete="new-password" />
                <button type="button" className="icon-btn icon-btn-plain" style={{ width: 36, height: 36 }}
                        aria-label={showPassword ? 'Şifreyi gizle' : 'Şifreyi göster'} onClick={() => setShowPassword(v => !v)}>
                  {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </span>
            </div>

            {error && <Alert tone="danger"><span>{error}</span></Alert>}

            <button className="btn btn-primary btn-lg btn-block" disabled={busy} style={{ marginTop: 6 }}>
              {busy ? <Spinner /> : 'Şifremi yenile'}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" style={{ alignSelf: 'center' }}
                    disabled={busy || resendIn > 0} onClick={() => void sendCode()}>
              {resendIn > 0 ? `Kodu tekrar gönder (${resendIn} sn)` : 'Kodu tekrar gönder'}
            </button>
          </form>
        )}
      </div>
    </div>
  )
}

function errorText(e: unknown): string {
  if (e instanceof ApiRequestError) {
    if (e.status === 0) return 'Sunucuya ulaşılamıyor. İnternet bağlantını kontrol et.'
    if (e.status === 429) return 'Çok fazla deneme yaptın. Bir dakika sonra tekrar dene.'
    if (e.errors.newPassword) return 'Şifre en az 8 karakter olmalı.'
    if (e.errors.code) return 'Kod 6 haneli olmalı.'
    if (e.errors.email) return 'Geçerli bir e-posta adresi gir.'
    if (e.status === 400) return 'Kod hatalı ya da süresi dolmuş. Yeni bir kod isteyebilirsin.'
    return e.message
  }
  return 'Bir hata oluştu.'
}
