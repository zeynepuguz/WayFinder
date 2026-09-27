import type { Plan } from '../api/types'

vi.mock('@capacitor/core', () => ({ Capacitor: { isNativePlatform: () => true } }))
vi.mock('../api', () => ({ api: { plans: vi.fn(), verifyGooglePlay: vi.fn() } }))

const PLAN: Plan = { plan: 'DAILY', label: 'Günlük', productId: 'nomi_pass_daily', priceTry: 25, days: 1 }

// A device without Google Play services: initialize() never settles
function installHangingStore() {
  const store = {
    obfuscator: '',
    register: vi.fn(),
    when: () => ({ approved: vi.fn() }),
    initialize: vi.fn(() => new Promise(() => {})),
    get: vi.fn(),
  }
  ;(window as unknown as { CdvPurchase: unknown }).CdvPurchase = {
    store,
    ProductType: { CONSUMABLE: 'consumable' },
    Platform: { GOOGLE_PLAY: 'android-playstore' },
    ErrorCode: { PAYMENT_CANCELLED: 6777006 },
  }
  return store
}

describe('purchase on a device without Google Play', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.resetModules()
  })
  afterEach(() => vi.useRealTimers())

  it('gives up with a clear message instead of spinning forever, and can retry', async () => {
    const store = installHangingStore()
    const { api } = await import('../api')
    vi.mocked(api.plans).mockResolvedValue({ plans: [PLAN], devMode: false })
    const { purchase } = await import('./billing')

    const first = purchase(PLAN, 7, false)
    const firstResult = expect(first).rejects.toThrow(/Google Play ödeme hizmetine ulaşılamadı/)
    await vi.advanceTimersByTimeAsync(15_000)
    await firstResult

    const second = purchase(PLAN, 7, false)
    const secondResult = expect(second).rejects.toThrow(/Google Play/)
    await vi.advanceTimersByTimeAsync(15_000)
    await secondResult

    // Retried the start, but registered the products only once
    expect(store.initialize).toHaveBeenCalledTimes(2)
    expect(store.register).toHaveBeenCalledTimes(1)
  })
})
