import { CloudRain, Crown, MessageCircle, Route, ShieldCheck, Sparkles, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { api } from '../api'
import type { AccessPlan, Plan } from '../api/types'
import { Alert, Spinner, useToast } from '../components/ui'
import { useAuth } from '../context/AuthContext'
import { isNativeApp, purchase, PurchaseCancelled, restorePurchases, storePrices } from '../lib/billing'
import { errorMessage, formatDateTime } from '../lib/format'
import { useAsync } from '../lib/useAsync'
import { locale, useLang, useT, type Translate } from '../lib/i18n'
import { safeNext } from '../lib/nav'

const perks = (t: Translate) => [
  { icon: Sparkles, text: t('Sınırsız AI asistan: “2 kişiyiz, 700 TL’miz var…” de, gerisini Nomi planlasın', 'Unlimited AI assistant: say “We’re 2 people with 700 TL…” and Nomi plans the rest') },
  { icon: Route, text: t('Bütçene, zamanına ve yürüme isteğine göre günlük rotalar', 'Day routes that fit your budget, time and how much you like to walk') },
  { icon: CloudRain, text: t('Hava, yorgunluk ve değişikliklere göre anlık yeniden planlama', 'Instant re-planning for weather, tiredness and change of plans') },
  { icon: MessageCircle, text: t('Rotalarını ve mekanları kaydet, istediğin zaman aç', 'Save your routes and places, open them anytime') },
]

// Store listing of the Android app; the website links to it instead of selling Premium itself
const PLAY_STORE_URL = import.meta.env.VITE_PLAY_STORE_URL as string | undefined

const planFlags = (t: Translate): Partial<Record<AccessPlan, { label: string; tone: string }>> => ({
  WEEKLY: { label: t('Popüler', 'Popular'), tone: 'badge-brand' },
  YEARLY: { label: t('En avantajlı', 'Best value'), tone: 'badge-premium' },
})

// Plan names come from the backend in Turkish; English visitors see these instead
const PLAN_LABELS_EN: Record<AccessPlan, string> = {
  DAILY: 'Daily',
  WEEKLY: 'Weekly',
  MONTHLY: 'Monthly',
  YEARLY: 'Yearly',
}

export function PremiumPage() {
  const { user, hasAccess } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const t = useT()
  const { lang } = useLang()
  const [params] = useSearchParams()
  const next = safeNext(params.get('next'))

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
  const priceText = (p: Plan) => prices[p.productId] ?? `${p.priceTry.toLocaleString(locale())} TL`
  const planLabel = (p: Plan) => (lang === 'en' ? PLAN_LABELS_EN[p.plan] ?? p.label : p.label)
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
      toast(t(
        `Premium aktif! ${status.expiresAt ? formatDateTime(status.expiresAt) + ' tarihine kadar' : ''}`,
        `Premium is active! ${status.expiresAt ? 'Until ' + formatDateTime(status.expiresAt) : ''}`,
      ))
      navigate(next ?? '/', { replace: true })
    } catch (e) {
      if (!(e instanceof PurchaseCancelled)) {
        setPurchaseError(errorMessage(e, t('Satın alma tamamlanamadı', 'Purchase couldn’t be completed')))
      }
    } finally {
      setBusy(false)
    }
  }

  async function restore() {
    setBusy(true)
    try {
      await restorePurchases()
      toast(t('Satın alımlar kontrol edildi', 'Purchases checked'))
    } catch (e) {
      setPurchaseError(errorMessage(e, t('Satın alımlar kontrol edilemedi', 'Couldn’t check purchases')))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="screen screen-no-tabbar" style={{ paddingBottom: 'calc(var(--safe-bottom) + 150px)' }}>
      <section className="paywall-hero">
        <div className="row-between">
          <span />
          <button className="icon-btn icon-btn-glass" aria-label={t('Kapat', 'Close')} onClick={() => navigate(-1)}><X size={20} /></button>
        </div>
        <span className="crown"><Crown size={30} /></span>
        <div className="stack-sm">
          <h1 className="t-display" style={{ color: '#fff' }}>Nomi Premium</h1>
          <p style={{ color: 'rgb(255 255 255 / 75%)' }}>{t('Şehirde gününü planlayan kişisel asistanın. Ödediğin süre kadar kullan; abonelik yok, sürpriz ödeme yok.', 'Your personal assistant that plans your day in the city. Use it for as long as you pay; no subscription, no surprise charges.')}</p>
        </div>
        <ul className="perks">
          {perks(t).map(({ icon: Icon, text }) => (
            <li key={text}><span className="perk-icon"><Icon size={17} /></span>{text}</li>
          ))}
        </ul>
      </section>

      {unlimited && (
        <Alert tone="success" icon={ShieldCheck}>
          <strong>{t('Hesabında süresiz ücretsiz erişim var', 'Your account has unlimited free access')}</strong>
          <p>{t('Paket satın alman gerekmiyor, tüm özellikleri kullanabilirsin.', 'No need to buy a pass; you can use every feature.')}</p>
        </Alert>
      )}

      {!unlimited && hasAccess && user?.access.expiresAt && (
        <Alert tone="success" icon={ShieldCheck}>
          <strong>{t(`Premium aktif · ${formatDateTime(user.access.expiresAt)} tarihine kadar`, `Premium active · until ${formatDateTime(user.access.expiresAt)}`)}</strong>
          <p>{t('Şimdi alacağın paket mevcut sürenin sonuna eklenir.', 'A pass you buy now is added to the end of your current one.')}</p>
        </Alert>
      )}

      {error && <Alert tone="danger"><span>{error}</span></Alert>}

      <section className="plans" aria-label={t('Paketler', 'Passes')} role="radiogroup">
        {loading && <Spinner />}
        {data?.plans.map(p => {
          const flag = planFlags(t)[p.plan]
          const perDay = p.priceTry / p.days
          return (
            <button key={p.plan} role="radio" aria-checked={selected === p.plan}
                    className={`plan ${selected === p.plan ? 'active' : ''}`} onClick={() => setSelected(p.plan)}>
              {flag && <span className={`badge ${flag.tone} plan-flag`}>{flag.label}</span>}
              <span className="plan-radio" />
              <span className="grow">
                <span className="t-headline" style={{ display: 'block' }}>{planLabel(p)}</span>
                <span className="t-caption">{p.days === 1 ? t('24 saat erişim', '24-hour access') : t(`${p.days} gün erişim`, `${p.days}-day access`)}</span>
              </span>
              <span className="plan-price">
                <strong>{priceText(p)}</strong>
                <span className="t-caption">
                  {p.days === 1 ? t('tek seferlik', 'one-time') : t(
                    `günlük ~${perDay.toLocaleString(locale(), { maximumFractionDigits: 1 })} TL`,
                    `~${perDay.toLocaleString(locale(), { maximumFractionDigits: 1 })} TL a day`,
                  )}
                </span>
              </span>
            </button>
          )
        })}
      </section>

      {purchaseError && <Alert tone="danger"><span>{purchaseError}</span></Alert>}

      <p className="fine-print">
        {t('Tek seferlik ödemedir ve otomatik olarak yenilenmez. Süre bittiğinde dilersen yeni bir paket alabilirsin.', 'This is a one-time payment and does not renew automatically. When it ends, you can buy a new pass if you like.')}
        {canBuy
          ? t(' Ödeme Google Play hesabın üzerinden alınır.', ' Payment is taken through your Google Play account.')
          : t(' Premium yalnızca Nomi Android uygulamasında Google Play ile satılır; aldığın paket bu web sitesinde de geçerlidir.', ' Premium is sold only in the Nomi Android app via Google Play; your pass also works on this website.')}
      </p>
      {isNativeApp() && user && (
        <button className="btn btn-ghost btn-sm" style={{ alignSelf: 'center' }} onClick={restore} disabled={busy}>
          {t('Satın alımları geri yükle', 'Restore purchases')}
        </button>
      )}

      <div className="sticky-cta" style={{ flexDirection: 'column', gap: 8 }}>
        {unlimited ? (
          <button className="btn btn-primary btn-lg btn-block" onClick={() => navigate(next ?? '/', { replace: true })}>
            {t('Devam et', 'Continue')}
          </button>
        ) : !canBuy ? (
          // Website: a showcase, Premium is sold only in the Android app
          PLAY_STORE_URL ? (
            <a className="btn btn-premium btn-lg btn-block" href={PLAY_STORE_URL} target="_blank" rel="noreferrer">
              {t('Google Play’den indir', 'Get it on Google Play')}
            </a>
          ) : (
            <Alert tone="info"><span>{t('Nomi Android uygulaması yakında Google Play’de. Premium’u oradan alabileceksin.', 'The Nomi Android app is coming soon to Google Play. You’ll be able to get Premium there.')}</span></Alert>
          )
        ) : (
          <button className="btn btn-premium btn-lg btn-block" disabled={busy || !plan} onClick={buy}>
            {busy ? <Spinner /> : !user ? t('Hesap oluştur ve devam et', 'Create an account and continue')
              : plan ? t(`${plan.label} erişimi al · ${priceText(plan)}`, `Get ${planLabel(plan)} access · ${priceText(plan)}`) : t('Paket seç', 'Choose a pass')}
          </button>
        )}
      </div>
    </main>
  )
}
