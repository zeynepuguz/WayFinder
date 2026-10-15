import { resetNativeShell, setupNativeShell } from './native'

const listeners: (() => void)[] = []
const exitApp = vi.fn()
vi.mock('@capacitor/app', () => ({
  App: {
    addListener: vi.fn((_event: string, listener: () => void) => { listeners.push(listener); return Promise.resolve({ remove: vi.fn() }) }),
    exitApp: () => exitApp(),
  },
}))
vi.mock('@capacitor/splash-screen', () => ({ SplashScreen: { hide: vi.fn(() => Promise.resolve()) } }))
vi.mock('@capacitor/status-bar', () => ({
  StatusBar: { setStyle: vi.fn(() => Promise.resolve()), setBackgroundColor: vi.fn(() => Promise.resolve()) },
  Style: { Dark: 'DARK', Light: 'LIGHT' },
}))
vi.mock('./billing', () => ({ isNativeApp: () => true }))

describe('Android back button', () => {
  beforeEach(() => {
    listeners.length = 0
    exitApp.mockReset()
    resetNativeShell()
  })

  it('one press goes back one step, however often the shell was set up (once per page change)', async () => {
    const first = vi.fn(() => true)
    const latest = vi.fn(() => true)
    await setupNativeShell(first)
    // navigate changes on every page change, so the app sets the shell up again with a new handler
    await setupNativeShell(vi.fn(() => true))
    await setupNativeShell(latest)

    expect(listeners).toHaveLength(1)
    listeners[0]()
    expect(latest).toHaveBeenCalledTimes(1)
    expect(first).not.toHaveBeenCalled()
    expect(exitApp).not.toHaveBeenCalled()
  })

  it('leaves the app from Ana Sayfa', async () => {
    await setupNativeShell(() => false)
    listeners[0]()
    expect(exitApp).toHaveBeenCalledTimes(1)
  })
})
