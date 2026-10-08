import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api } from '../api'
import { setRenewedHandler, setUnauthorizedHandler, tokenStore } from '../api/client'
import type { AccessStatus, AuthResponse, CodePurpose, CodeSent, RegisterRequest, User } from '../api/types'
import { setAccessChangedHandler } from '../lib/billing'

interface AuthState {
  user: User | null
  loading: boolean
  // Signed in and has a running pass
  hasAccess: boolean
  // Step one: the password / sign-up form; a code goes to the e-mail
  login: (email: string, password: string) => Promise<CodeSent>
  register: (request: RegisterRequest) => Promise<CodeSent>
  // Step two: the e-mailed code signs in
  verifyCode: (purpose: CodePurpose, email: string, code: string) => Promise<void>
  // Sets a new password with the e-mailed code and signs in
  resetPassword: (email: string, code: string, newPassword: string) => Promise<void>
  logout: () => void
  deleteAccount: () => Promise<void>
  refreshUser: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

const hasSession = () => tokenStore.get() !== null || tokenStore.getRefresh() !== null

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(hasSession)

  // The session ended (expired, signed out elsewhere): forget the tokens here
  const forget = useCallback(() => {
    tokenStore.set(null)
    setUser(null)
  }, [])

  // Signing out also ends the session on the server, so the refresh token cannot be used again
  const logout = useCallback(() => {
    const refreshToken = tokenStore.getRefresh()
    if (refreshToken) void api.logout(refreshToken).catch(() => {})
    forget()
  }, [forget])

  const accept = useCallback((response: AuthResponse) => {
    tokenStore.set(response.accessToken, response.refreshToken)
    setUser(response.user)
  }, [])

  const refreshUser = useCallback(async () => {
    setUser(await api.me())
  }, [])

  // Restore the session from saved tokens (renewed by the client when the access token ran out);
  // keep access in sync after purchases
  useEffect(() => {
    setUnauthorizedHandler(forget)
    setRenewedHandler(setUser)
    setAccessChangedHandler((access: AccessStatus) => setUser(current => current ? { ...current, access } : current))
    if (!hasSession()) return
    api.me()
      .then(setUser)
      // Only a finished session signs out (the client calls forget); offline keeps the tokens for the next start
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [forget])

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
    login: (email, password) => api.login(email, password),
    register: request => api.register(request),
    verifyCode: async (purpose, email, code) =>
      accept(await (purpose === 'SIGN_UP' ? api.verifyRegister(email, code) : api.verifyLogin(email, code))),
    resetPassword: async (email, code, newPassword) => accept(await api.resetPassword(email, code, newPassword)),
    logout,
    deleteAccount: async () => {
      await api.deleteAccount()
      forget()
    },
    refreshUser,
  }), [user, loading, hasAccess, accept, logout, forget, refreshUser])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
