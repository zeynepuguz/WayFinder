import { AlertTriangle, CheckCircle2, ChevronLeft, Info, LoaderCircle, Minus, Plus, X, type LucideIcon } from 'lucide-react'
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { useT } from '../lib/i18n'

export function Skeleton({ height = 16, width = '100%', radius }: { height?: number; width?: number | string; radius?: number }) {
  return <div className="skeleton" style={{ height, width, borderRadius: radius }} aria-hidden />
}

export function ListSkeleton({ rows = 3, height = 96 }: { rows?: number; height?: number }) {
  const t = useT()
  return (
    <div className="stack" role="status" aria-label={t('Yükleniyor', 'Loading')}>
      {Array.from({ length: rows }, (_, i) => <Skeleton key={i} height={height} radius={20} />)}
    </div>
  )
}

export function Spinner({ size = 18 }: { size?: number }) {
  const t = useT()
  return <LoaderCircle size={size} className="spin" aria-label={t('Yükleniyor', 'Loading')} />
}

type AlertTone = 'info' | 'warning' | 'success' | 'danger'
const ALERT_ICON: Record<AlertTone, LucideIcon> = { info: Info, warning: AlertTriangle, success: CheckCircle2, danger: AlertTriangle }

export function Alert({ tone = 'info', icon, children, action }: {
  tone?: AlertTone
  icon?: LucideIcon
  children: ReactNode
  action?: ReactNode
}) {
  const Icon = icon ?? ALERT_ICON[tone]
  return (
    <div className={`alert alert-${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      <Icon size={18} />
      <div className="grow stack-sm">{children}</div>
      {action}
    </div>
  )
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  const t = useT()
  return (
    <Alert tone="danger" action={onRetry && <button className="btn btn-sm btn-secondary" onClick={onRetry}>{t('Tekrar dene', 'Try again')}</button>}>
      <span>{message}</span>
    </Alert>
  )
}

export function EmptyState({ icon: Icon, title, text, action }: {
  icon: LucideIcon
  title: string
  text?: string
  action?: ReactNode
}) {
  return (
    <div className="empty">
      <div className="empty-art"><Icon size={32} strokeWidth={1.8} /></div>
      <h3 className="t-headline">{title}</h3>
      {text && <p className="t-caption" style={{ maxWidth: 280 }}>{text}</p>}
      {action}
    </div>
  )
}

export function BackButton({ to, glass }: { to?: string; glass?: boolean }) {
  const navigate = useNavigate()
  const t = useT()
  return (
    <button className={`icon-btn ${glass ? 'icon-btn-glass' : ''}`} aria-label={t('Geri', 'Back')}
            onClick={() => (to ? navigate(to) : window.history.length > 1 ? navigate(-1) : navigate('/'))}>
      <ChevronLeft size={22} />
    </button>
  )
}

export function Segmented<T extends string>({ value, options, onChange }: {
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
}) {
  return (
    <div className="segmented" role="tablist">
      {options.map(o => (
        <button key={o.value} role="tab" aria-selected={value === o.value} className={value === o.value ? 'active' : ''}
                onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Stepper({ value, min, max, onChange, label }: {
  value: number
  min: number
  max: number
  onChange: (value: number) => void
  label: string
}) {
  const t = useT()
  return (
    <div className="stepper" aria-label={label}>
      <button type="button" aria-label={t('Azalt', 'Decrease')} disabled={value <= min} onClick={() => onChange(value - 1)}><Minus size={18} /></button>
      <strong aria-live="polite">{value}</strong>
      <button type="button" aria-label={t('Arttır', 'Increase')} disabled={value >= max} onClick={() => onChange(value + 1)}><Plus size={18} /></button>
    </div>
  )
}

export function Sheet({ open, onClose, children, label }: {
  open: boolean
  onClose: () => void
  children: ReactNode
  label: string
}) {
  const t = useT()
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label={label} onClick={e => e.stopPropagation()}>
        <div className="sheet-grip" />
        <div className="row-between" style={{ marginBottom: 12 }}>
          <h2 className="t-title">{label}</h2>
          <button className="icon-btn icon-btn-plain" aria-label={t('Kapat', 'Close')} onClick={onClose}><X size={20} /></button>
        </div>
        {children}
      </div>
    </div>
  )
}

// ---------- toasts ----------

const ToastContext = createContext<(message: string) => void>(() => {})

export function ToastProvider({ children }: { children: ReactNode }) {
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    if (!message) return
    const timer = setTimeout(() => setMessage(null), 2600)
    return () => clearTimeout(timer)
  }, [message])

  const show = useCallback((text: string) => setMessage(text), [])

  return (
    <ToastContext.Provider value={show}>
      {children}
      {message && <div className="toast" role="status"><CheckCircle2 size={18} />{message}</div>}
    </ToastContext.Provider>
  )
}

export function useToast() {
  return useContext(ToastContext)
}
