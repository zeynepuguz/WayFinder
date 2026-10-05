/**
 * Buying a Nomi pass.
 *
 * Android app: Google Play Billing (cordova-plugin-purchase). Every pass is a consumable
 * in-app product. After Google approves the payment, the BACKEND verifies the purchase token
 * with Google and only then grants access; the app never unlocks anything by itself.
 *
 * Web: there is no store. Purchases only work when the backend runs with BILLING_DEV_MODE=true.
 */
import { Capacitor } from '@capacitor/core'
import { api } from '../api'
import type { AccessStatus, Plan } from '../api/types'
import { errorMessage } from './format'
import { tr } from './i18n'

export function isNativeApp(): boolean {
  return Capacitor.isNativePlatform()
}

// ---- minimal typing of the parts of CdvPurchase we use (global injected by the Cordova plugin) ----

interface CdvTransaction {
  products: { id: string }[]
  purchaseId?: string
  parentReceipt?: { purchaseToken?: string }
  finish(): Promise<unknown>
}

interface CdvOffer {
  order(data?: { applicationUsername?: string }): Promise<{ isError: true; code: number; message: string } | undefined>
}

interface CdvProduct {
  id: string
  pricing?: { price: string }
  getOffer(): CdvOffer | undefined
}

interface CdvStore {
  obfuscator?: string
  register(products: { id: string; type: string; platform: string }[]): void
  when(): { approved(cb: (t: CdvTransaction) => void): unknown }
  initialize(platforms: string[]): Promise<unknown[]>
  get(id: string, platform?: string): CdvProduct | undefined
  restorePurchases(): Promise<unknown>
}

interface CdvPurchaseGlobal {
  store: CdvStore
  ProductType: { CONSUMABLE: string }
  Platform: { GOOGLE_PLAY: string }
  ErrorCode: { PAYMENT_CANCELLED: number }
}

declare global {
  interface Window {
    CdvPurchase?: CdvPurchaseGlobal
  }
}

type Waiter = { resolve: (status: AccessStatus) => void; reject: (error: Error) => void }

let initialized: Promise<CdvStore> | null = null
const waiting = new Map<string, Waiter>()
let onAccessChanged: (status: AccessStatus) => void = () => {}

export function setAccessChangedHandler(handler: (status: AccessStatus) => void) {
  onAccessChanged = handler
}

// Without Google Play services (no Play Store, no Google account, some brands) the store never
// finishes starting; give up after this so the user gets an answer instead of a spinner
const INIT_TIMEOUT_MS = 15_000
const storeUnavailable = () => tr(
  'Google Play ödeme hizmetine ulaşılamadı. Cihazında Play Store yüklü ve Google hesabın açık olmalı; sonra tekrar dene.',
  'Couldn’t reach Google Play billing. Make sure the Play Store is installed and you’re signed in to a Google account, then try again.',
)
let registered = false

function nativeStore(plans: Plan[]): Promise<CdvStore> {
  if (initialized) return initialized

  const starting = new Promise<CdvStore>((resolve, reject) => {
    const start = async () => {
      const cdv = window.CdvPurchase
      if (!cdv) {
        reject(new Error(tr('Ödeme altyapısı yüklenemedi', 'Couldn’t load the payment service')))
        return
      }
      const { store, Platform } = cdv
      if (!registered) {
        registerProducts(cdv, plans)
        registered = true
      }
      await store.initialize([Platform.GOOGLE_PLAY])
      resolve(store)
    }

    // Cordova plugins are ready after "deviceready"
    if (window.CdvPurchase) void start()
    else document.addEventListener('deviceready', () => void start(), { once: true })
  })

  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(storeUnavailable())), INIT_TIMEOUT_MS)
  })
  initialized = Promise.race([starting, timeout]).finally(() => clearTimeout(timer))
  // A failed start can be retried with the next purchase attempt
  initialized.catch(() => { initialized = null })
  return initialized
}

function registerProducts(cdv: CdvPurchaseGlobal, plans: Plan[]) {
  const { store, ProductType, Platform } = cdv

  // Send our user id as-is; the backend checks that a purchase belongs to the caller
  store.obfuscator = 'disabled'
  store.register(plans.map(p => ({ id: p.productId, type: ProductType.CONSUMABLE, platform: Platform.GOOGLE_PLAY })))

  // Also fires on app start for purchases that were paid but not verified yet (e.g. app was killed)
  store.when().approved(async transaction => {
    const productId = transaction.products[0]?.id
    const token = transaction.parentReceipt?.purchaseToken ?? transaction.purchaseId
    if (!productId || !token) return

    const waiter = waiting.get(productId)
    try {
      const status = await api.verifyGooglePlay(productId, token)
      // Consume only after the server granted access, so a failed check can be retried
      await transaction.finish()
      onAccessChanged(status)
      waiter?.resolve(status)
    } catch (e) {
      waiter?.reject(e instanceof Error ? e : new Error(tr('Satın alma doğrulanamadı', 'Couldn’t verify the purchase')))
    } finally {
      waiting.delete(productId)
    }
  })
}

/** Store-localized price (Google requires showing the price from Play), or null on web. */
export async function storePrices(plans: Plan[]): Promise<Record<string, string>> {
  if (!isNativeApp()) return {}
  try {
    const store = await nativeStore(plans)
    const cdv = window.CdvPurchase!
    return Object.fromEntries(plans
      .map(p => [p.productId, store.get(p.productId, cdv.Platform.GOOGLE_PLAY)?.pricing?.price])
      .filter((entry): entry is [string, string] => Boolean(entry[1])))
  } catch {
    return {}
  }
}

export class PurchaseCancelled extends Error {}

export async function purchase(plan: Plan, userId: number, devMode: boolean): Promise<AccessStatus> {
  if (!isNativeApp()) {
    if (!devMode) throw new Error(tr('Satın alma yalnızca Nomi Android uygulamasında yapılabilir.', 'Purchases are only available in the Nomi Android app.'))
    const status = await api.devPurchase(plan.plan)
    onAccessChanged(status)
    return status
  }

  const plans = (await api.plans()).plans
  const store = await nativeStore(plans)
  const cdv = window.CdvPurchase!
  const offer = store.get(plan.productId, cdv.Platform.GOOGLE_PLAY)?.getOffer()
  if (!offer) throw new Error(tr('Bu paket şu an satın alınamıyor.', 'This pass can’t be bought right now.'))

  return new Promise<AccessStatus>((resolve, reject) => {
    waiting.set(plan.productId, { resolve, reject })
    const fail = (e: Error) => {
      waiting.delete(plan.productId)
      reject(e)
    }
    offer.order({ applicationUsername: String(userId) }).then(error => {
      if (error) fail(error.code === cdv.ErrorCode.PAYMENT_CANCELLED ? new PurchaseCancelled() : new Error(error.message))
    }, (e: unknown) => {
      // The plugin itself threw: settle here, or the purchase button would spin forever
      fail(new Error(errorMessage(e, tr('Satın alma başlatılamadı', 'Couldn’t start the purchase'))))
    })
  })
}

// Re-delivers purchases Google still holds (e.g. paid but the app closed before verification)
export async function restorePurchases(): Promise<void> {
  if (!isNativeApp()) return
  const store = await nativeStore((await api.plans()).plans)
  await store.restorePurchases()
}
