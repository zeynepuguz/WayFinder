import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { useGate } from './gate'

let auth: { user: object | null; hasAccess: boolean } = { user: null, hasAccess: false }
vi.mock('../context/AuthContext', () => ({ useAuth: () => auth }))

const action = vi.fn()

function PaidButton() {
  const gate = useGate()
  return <button onClick={() => gate() && action()}>Plan yap</button>
}

function Where() {
  const location = useLocation()
  return <p>at {location.pathname}{location.search}</p>
}

function renderGate() {
  render(
    <MemoryRouter initialEntries={['/assistant']}>
      <Routes>
        <Route path="/assistant" element={<PaidButton />} />
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )
  return userEvent.click(screen.getByRole('button', { name: 'Plan yap' }))
}

describe('useGate', () => {
  beforeEach(() => action.mockReset())

  it('sends guests to login and brings them back afterwards', async () => {
    auth = { user: null, hasAccess: false }
    await renderGate()
    expect(action).not.toHaveBeenCalled()
    expect(screen.getByText('at /login?next=%2Fassistant')).toBeInTheDocument()
  })

  it('sends signed-in users without a pass to the paywall', async () => {
    auth = { user: { id: 1 }, hasAccess: false }
    await renderGate()
    expect(action).not.toHaveBeenCalled()
    expect(screen.getByText('at /premium?next=%2Fassistant')).toBeInTheDocument()
  })

  it('lets users with an active pass through', async () => {
    auth = { user: { id: 1 }, hasAccess: true }
    await renderGate()
    expect(action).toHaveBeenCalled()
  })
})
