import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { ApiRequestError } from '../api/client'
import { AuthPage } from './AuthPage'

const login = vi.fn()
const register = vi.fn()

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ login, register }),
}))

function renderAt(url: string) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/login" element={<AuthPage />} />
        <Route path="/premium" element={<h1>Paywall</h1>} />
        <Route path="/" element={<h1>Home</h1>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AuthPage', () => {
  beforeEach(() => {
    login.mockReset()
    register.mockReset()
  })

  it('logs in and goes back to where the user came from', async () => {
    login.mockResolvedValue(undefined)
    renderAt('/login?next=%2Fpremium')

    await userEvent.type(screen.getByLabelText('E-posta'), 'a@b.dev')
    await userEvent.type(screen.getByLabelText('Şifre'), 'sifre1234')
    await userEvent.click(screen.getByRole('button', { name: 'Giriş yap' }))

    expect(login).toHaveBeenCalledWith('a@b.dev', 'sifre1234')
    expect(await screen.findByRole('heading', { name: 'Paywall' })).toBeInTheDocument()
  })

  it('shows a Turkish message for wrong credentials', async () => {
    login.mockRejectedValue(new ApiRequestError({ status: 401, message: 'Invalid email or password', errors: {} }))
    renderAt('/login')

    await userEvent.type(screen.getByLabelText('E-posta'), 'a@b.dev')
    await userEvent.type(screen.getByLabelText('Şifre'), 'yanlis')
    await userEvent.click(screen.getByRole('button', { name: 'Giriş yap' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('E-posta veya şifre hatalı.')
  })

  it('opens directly in register mode and asks for a name', async () => {
    register.mockResolvedValue(undefined)
    renderAt('/login?mode=register')

    await userEvent.type(screen.getByLabelText('Adın'), 'Zeynep')
    await userEvent.type(screen.getByLabelText('E-posta'), 'z@b.dev')
    await userEvent.type(screen.getByLabelText('Şifre'), 'sifre1234')
    await userEvent.click(screen.getByRole('button', { name: 'Hesap oluştur' }))

    expect(register).toHaveBeenCalledWith('z@b.dev', 'sifre1234', 'Zeynep')
    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument()
  })
})
