import { CloudRain, Crown, MessageCircle, Route, ShieldCheck, Sparkles, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { api } from '../api'
import type { AccessPlan, Plan } from '../api/types'
import { Alert, Spinner, useToast } from '../components/ui'
import { useAuth } from '../context/AuthContext'
import { isNativeApp, purchase, PurchaseCancelled, restorePurchases, storePrices } from '../lib/billing'
import { formatDateTime } from '../lib/format'
import { useAsync } from '../lib/useAsync'

const PERKS = [
  { icon: Sparkles, text: 'Sınırsız AI asistan: “2 kişiyiz, 700 TL’miz var…” de, gerisini Nomi planlasın' },
  { icon: Route, text: 'Bütçene, zamanına ve yürüme isteğine göre günlük rotalar' },
  { icon: CloudRain, text: 'Hava, yorgunluk ve değişikliklere göre anlık yeniden planlama' },
  { icon: MessageCircle, text: 'Rotalarını ve mekanları kaydet, istediğin zaman aç' },
]

const PLAN_FLAGS: Partial<Record<AccessPlan, { label: string; tone: string }>> = {
  WEEKLY: { label: 'Popüler', tone: 'badge-brand' },
  YEARLY: { label: 'En avantajlı', tone: 'badge-premium' },
}

export function PremiumPage() {
  const { user, hasAccess } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [params] = useSearchParams()
  const next = params.get('next')

  const { data, error, loading } = useAsync(() => api.plans(), [])
  const [selected, setSelected] = useState<AccessPlan>('WEEKLY')
  const [prices, setPrices] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState(false)
  const [purchaseError, setPurchaseError] = useState<string | null>(null)

  // On Android the price shown must be the one Google Play will charge
  useEffect(() => {
    if (data) void storePrices(data.plans).then(setPrices)
  }, [data])

  const plan = data?.plans.find(p => p.plan === selected)
  const priceText = (p: Plan) => prices[p.productId] ?? `${p.priceTry.toLocaleString('tr-TR')} TL`
  const canBuy = isNativeApp() || data?.devMode
  // Owner/admin accounts never pay, so they must never reach the purchase flow
  const unlimited = Boolean(user && (user.role === 'ADMIN' || user.access.free))

  async function buy() {
    if (!plan || unlimited) return
    if (!user) {
      navigate(`/login?mode=register&next=${encodeURIComponent(`/premium${next ? `?next=${encodeURIComponent(next)}` : ''}`)}`)
      return
    }
    setBusy(true)
    setPurchaseError(null)
    try {
      const status = await purchase(plan, user.id, Boolean(data?.devMode))
      toast(`Premium aktif! ${status.expiresAt ? formatDateTime(status.expiresAt) + ' tarihine kadar' : ''}`)
      navigate(next ?? '/', { replace: true })
    } catch (e) {
      if (!(e instanceof PurchaseCancelled)) {
        setPurchaseError(e instanceof Error ? e.message : 'Satın alma tamamlanamadı')
      }
    } finally {
      setBusy(false)
    }
  }

  async function restore() {
    setBusy(true)
    try {
      await restorePurchases()
      toast('Satın alımlar kontrol edildi')
    } catch (e) {
      setPurchaseError(e instanceof Error ? e.message : 'Satın alımlar kontrol edilemedi')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="screen screen-no-tabbar" style={{ paddingBottom: 'calc(var(--safe-bottom) + 150px)' }}>
      <section className="paywall-hero">
        <div className="row-between">
          <span />
          <button className="icon-btn icon-btn-glass" aria-label="Kapat" onClick={() => navigate(-1)}><X size={20} /></button>
        </div>
        <span className="crown"><Crown size={30} /></span>
        <div className="stack-sm">
          <h1 className="t-display" style={{ color: '#fff' }}>Nomi Premium</h1>
          <p style={{ color: 'rgb(255 255 255 / 75%)' }}>Şehirde gününü planlayan kişisel asistanın. Ödediğin süre kadar kullan; abonelik yok, sürpriz ödeme yok.</p>
        </div>
        <ul className="perks">
          {PERKS.map(({ icon: Icon, text }) => (
            <li key={text}><span className="perk-icon"><Icon size={17} /></span>{text}</li>
          ))}
        </ul>
      </section>

      {unlimited && (
        <Alert tone="success" icon={ShieldCheck}>
          <strong>Hesabında süresiz ücretsiz erişim var</strong>
          <p>Paket satın alman gerekmiyor, tüm özellikleri kullanabilirsin.</p>
        </Alert>
      )}

      {!unlimited && hasAccess && user?.access.expiresAt && (
        <Alert tone="success" icon={ShieldCheck}>
          <strong>Premium aktif · {formatDateTime(user.access.expiresAt)} tarihine kadar</strong>
          <p>Şimdi alacağın paket mevcut sürenin sonuna eklenir.</p>
        </Alert>
      )}

      {error && <Alert tone="danger"><span>{error}</span></Alert>}

      <section className="plans" aria-label="Paketler" role="radiogroup">
        {loading && <Spinner />}
        {data?.plans.map(p => {
          const flag = PLAN_FLAGS[p.plan]
          const perDay = p.priceTry / p.days
          return (
            <button key={p.plan} role="radio" aria-checked={selected === p.plan}
                    className={`plan ${selected === p.plan ? 'active' : ''}`} onClick={() => setSelected(p.plan)}>
              {flag && <span className={`badge ${flag.tone} plan-flag`}>{flag.label}</span>}
              <span className="plan-radio" />
              <span className="grow">
                <span className="t-headline" style={{ display: 'block' }}>{p.label}</span>
                <span className="t-caption">{p.days === 1 ? '24 saat erişim' : `${p.days} gün erişim`}</span>
              </span>
              <span className="plan-price">
                <strong>{priceText(p)}</strong>
                <span className="t-caption">
                  {p.days === 1 ? 'tek seferlik' : `günlük ~${perDay.toLocaleString('tr-TR', { maximumFractionDigits: 1 })} TL`}
                </span>
              </span>
            </button>
          )
        })}
      </section>

      {purchaseError && <Alert tone="danger"><span>{purchaseError}</span></Alert>}

      <p className="fine-print">
        Tek seferlik ödemedir ve otomatik olarak yenilenmez. Süre bittiğinde dilersen yeni bir paket alabilirsin.
        Ödeme Google Play hesabın üzerinden alınır.
      </p>
      {isNativeApp() && user && (
        <button className="btn btn-ghost btn-sm" style={{ alignSelf: 'center' }} onClick={restore} disabled={busy}>
          Satın alımları geri yükle
        </button>
      )}

      <div className="sticky-cta" style={{ flexDirection: 'column', gap: 8 }}>
        {unlimited ? (
          <button className="btn btn-primary btn-lg btn-block" onClick={() => navigate(next ?? '/', { replace: true })}>
            Devam et
          </button>
        ) : !canBuy && user ? (
          <Alert tone="info"><span>Satın alma Nomi Android uygulamasında yapılabilir.</span></Alert>
        ) : (
          <button className="btn btn-premium btn-lg btn-block" disabled={busy || !plan} onClick={buy}>
            {busy ? <Spinner /> : !user ? 'Hesap oluştur ve devam et'
              : plan ? `${plan.label} erişimi al · ${priceText(plan)}` : 'Paket seç'}
          </button>
        )}
      </div>
    </main>
  )
}
