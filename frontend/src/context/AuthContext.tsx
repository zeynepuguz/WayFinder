import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api } from '../api'
import { setUnauthorizedHandler, tokenStore } from '../api/client'
import type { AccessStatus, AuthResponse, User } from '../api/types'
import { setAccessChangedHandler } from '../lib/billing'

interface AuthState {
  user: User | null
  loading: boolean
  // Signed in and has a running pass
  hasAccess: boolean
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string, displayName: string) => Promise<void>
  logout: () => void
  deleteAccount: () => Promise<void>
  refreshUser: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(() => tokenStore.get() !== null)

  const logout = useCallback(() => {
    tokenStore.set(null)
    setUser(null)
  }, [])

  const accept = useCallback((response: AuthResponse) => {
    tokenStore.set(response.accessToken)
    setUser(response.user)
  }, [])

  const refreshUser = useCallback(async () => {
    setUser(await api.me())
  }, [])

  // Restore the session from a saved token; keep access in sync after purchases
  useEffect(() => {
    setUnauthorizedHandler(logout)
    setAccessChangedHandler((access: AccessStatus) => setUser(current => current ? { ...current, access } : current))
    if (!tokenStore.get()) return
    api.me()
      .then(setUser)
      .catch(() => logout())
      .finally(() => setLoading(false))
  }, [logout])

  // A pass can run out while the app is open
  const expiresAt = user?.access.expiresAt
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!expiresAt) return
    const ms = new Date(expiresAt).getTime() - Date.now()
    if (ms <= 0 || ms > 2 ** 31 - 1) return
    const timer = setTimeout(() => setNow(Date.now()), ms + 1000)
    return () => clearTimeout(timer)
  }, [expiresAt])

  const hasAccess = Boolean(user && (user.role === 'ADMIN' || (user.access.active
    && (!user.access.expiresAt || new Date(user.access.expiresAt).getTime() > now))))

  const value = useMemo<AuthState>(() => ({
    user,
    loading,
    hasAccess,
    login: async (email, password) => accept(await api.login(email, password)),
    register: async (email, password, displayName) => accept(await api.register(email, password, displayName)),
    logout,
    deleteAccount: async () => {
      await api.deleteAccount()
      logout()
    },
    refreshUser,
  }), [user, loading, hasAccess, accept, logout, refreshUser])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
