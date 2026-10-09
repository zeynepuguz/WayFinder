// The Android / iOS app: tokens never stay in the WebView's localStorage
const secure = new Map<string, string>()

vi.mock('@capacitor/core', async importOriginal => ({
  ...(await importOriginal<typeof import('@capacitor/core')>()),
  Capacitor: { isNativePlatform: () => true, getPlatform: () => 'android' },
}))

vi.mock('@aparajita/capacitor-secure-storage', () => ({
  SecureStorage: {
    getItem: async (key: string) => secure.get(key) ?? null,
    setItem: async (key: string, value: string) => { secure.set(key, value) },
    removeItem: async (key: string) => { secure.delete(key) },
  },
}))

const { tokenStore } = await import('./client')

const flush = () => new Promise(resolve => setTimeout(resolve, 0))

describe('token store in the app', () => {
  beforeEach(() => {
    secure.clear()
    localStorage.clear()
    tokenStore.set(null)
  })

  it('keeps the refresh token in the secure store and the access token only in memory', async () => {
    tokenStore.set('access-1', 'refresh-1')
    await flush()

    expect(tokenStore.get()).toBe('access-1')
    expect(tokenStore.getRefresh()).toBe('refresh-1')
    expect(secure.get('nomi.refresh')).toBe('refresh-1')
    expect(localStorage.length).toBe(0)
  })

  it('reads the session back at app start', async () => {
    secure.set('nomi.refresh', 'saved-refresh')

    await tokenStore.load()

    expect(tokenStore.getRefresh()).toBe('saved-refresh')
    // Renewed with the refresh token on the first request
    expect(tokenStore.get()).toBeNull()
  })

  it('moves tokens of an older app version out of localStorage', async () => {
    localStorage.setItem('nomi.token', 'old-access')
    localStorage.setItem('nomi.refresh', 'old-refresh')

    await tokenStore.load()

    expect(secure.get('nomi.refresh')).toBe('old-refresh')
    expect(tokenStore.get()).toBe('old-access')
    expect(localStorage.getItem('nomi.token')).toBeNull()
    expect(localStorage.getItem('nomi.refresh')).toBeNull()
  })

  it('signing out removes the token from the secure store', async () => {
    tokenStore.set('access-1', 'refresh-1')
    tokenStore.set(null)
    await flush()

    expect(tokenStore.getRefresh()).toBeNull()
    expect(secure.has('nomi.refresh')).toBe(false)
  })
})

export {}
