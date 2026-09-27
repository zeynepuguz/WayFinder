import { useEffect, useRef } from 'react'
import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation, useNavigate } from 'react-router'
import { PAYMENT_REQUIRED_EVENT } from './api/client'
import { AppLayout } from './components/AppLayout'
import { Spinner, ToastProvider } from './components/ui'
import { AuthProvider, useAuth } from './context/AuthContext'
import { LocationProvider } from './context/LocationContext'
import { SavedPlacesProvider } from './context/SavedPlacesContext'
import { setupNativeShell } from './lib/native'
import { AssistantPage } from './pages/AssistantPage'
import { AuthPage } from './pages/AuthPage'
import { ForgotPasswordPage } from './pages/ForgotPasswordPage'
import { ExplorePage } from './pages/ExplorePage'
import { HomePage } from './pages/HomePage'
import { hasOnboarded, OnboardingPage } from './pages/OnboardingPage'
import { PlaceDetailPage } from './pages/PlaceDetailPage'
import { PremiumPage } from './pages/PremiumPage'
import { ProfilePage } from './pages/ProfilePage'
import { RouteDetailPage } from './pages/RouteDetailPage'
import { RoutesPage } from './pages/RoutesPage'
import { SavedPage } from './pages/SavedPage'

// First launch shows the intro; checked on every render (the flag changes when the intro ends)
function HomeEntry() {
  return hasOnboarded() ? <HomePage /> : <Navigate to="/welcome" replace />
}

// Screens without the tab bar
function FullScreen() {
  return <div className="app"><Outlet /></div>
}

// Android back button, splash screen, status bar; paywall when the backend answers 402
function ShellEffects() {
  const navigate = useNavigate()
  const location = useLocation()
  const path = useRef(location.pathname)
  path.current = location.pathname

  useEffect(() => {
    void setupNativeShell(() => {
      if (path.current === '/') return false
      navigate(-1)
      return true
    })
  }, [navigate])

  useEffect(() => {
    const onPaymentRequired = () => {
      if (!path.current.startsWith('/premium')) navigate(`/premium?next=${encodeURIComponent(path.current)}`)
    }
    window.addEventListener(PAYMENT_REQUIRED_EVENT, onPaymentRequired)
    return () => window.removeEventListener(PAYMENT_REQUIRED_EVENT, onPaymentRequired)
  }, [navigate])

  return null
}

function AppRoutes() {
  const { loading } = useAuth()

  if (loading) {
    return <div className="app" style={{ display: 'grid', placeItems: 'center', minHeight: '100vh' }}><Spinner size={28} /></div>
  }

  return (
    <>
      <ShellEffects />
      <Routes>
        <Route path="welcome" element={<OnboardingPage />} />
        <Route path="login" element={<AuthPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />
        <Route element={<FullScreen />}>
          <Route path="premium" element={<PremiumPage />} />
          <Route path="places/:id" element={<PlaceDetailPage />} />
          <Route path="profile" element={<ProfilePage />} />
        </Route>
        <Route element={<AppLayout />}>
          <Route index element={<HomeEntry />} />
          <Route path="explore" element={<ExplorePage />} />
          <Route path="assistant" element={<AssistantPage />} />
          <Route path="routes" element={<RoutesPage />} />
          <Route path="routes/:id" element={<RouteDetailPage />} />
          <Route path="saved" element={<SavedPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <LocationProvider>
            <SavedPlacesProvider>
              <AppRoutes />
            </SavedPlacesProvider>
          </LocationProvider>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  )
}
