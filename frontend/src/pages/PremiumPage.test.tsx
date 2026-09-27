import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import type { Plan } from '../api/types'
import { PremiumPage } from './PremiumPage'

const PLANS: Plan[] = [{ plan: 'WEEKLY', label: 'Haftalık', productId: 'nomi_pass_weekly', priceTry: 79, days: 7 }]

vi.mock('../api', () => ({ api: { plans: () => Promise.resolve({ plans: PLANS, devMode: false }) } }))
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 7, role: 'USER', access: { active: false, plan: null, expiresAt: null, free: false } },
    hasAccess: false,
  }),
}))
vi.mock('../lib/billing', () => ({
  isNativeApp: () => false,
  purchase: vi.fn(),
  PurchaseCancelled: class extends Error {},
  restorePurchases: vi.fn(),
  storePrices: () => Promise.resolve({}),
}))

describe('PremiumPage on the website', () => {
  it('shows the plans but sells nothing; Premium comes from the Android app', async () => {
    render(
      <MemoryRouter initialEntries={['/premium']}>
        <Routes>
          <Route path="/premium" element={<PremiumPage />} />
        </Routes>
      </MemoryRouter>,
    )

    expect(await screen.findByText('Haftalık')).toBeInTheDocument()
    expect(screen.getByText(/Nomi Android uygulaması yakında Google Play’de/)).toBeInTheDocument()
    expect(screen.getByText(/aldığın paket bu web sitesinde de geçerlidir/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /erişimi al/ })).not.toBeInTheDocument()
  })
})
