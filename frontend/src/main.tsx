import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { tokenStore } from './api/client'
import './styles.css'

// Error reports only when a DSN is set; the Sentry code is a separate chunk downloaded just then
const sentryDsn = import.meta.env.VITE_SENTRY_DSN
if (sentryDsn) void import('./lib/sentry').then(m => m.initSentry(sentryDsn))

// The app reads its saved session from the secure store first (instant on the web)
void tokenStore.load().finally(() => {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
})
