import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { ApiRequestError } from '../api/client'
import { AuthPage } from './AuthPage'

const login = vi.fn()
const register = vi.fn()
const verifyCode = vi.fn()

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({ login, register, verifyCode }),
}))

const sent = { email: 'a****@b.dev', validMinutes: 10, resendAfterSeconds: 60 }

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
    verifyCode.mockReset()
  })

  it('logs in with the e-mailed code and goes back to where the user came from', async () => {
    login.mockResolvedValue(sent)
    verifyCode.mockResolvedValue(undefined)
    renderAt('/login?next=%2Fpremium')

    await userEvent.type(screen.getByLabelText('E-posta'), 'a@b.dev')
    await userEvent.type(screen.getByLabelText('Şifre'), 'sifre1234')
    await userEvent.click(screen.getByRole('button', { name: 'Giriş yap' }))

    expect(login).toHaveBeenCalledWith('a@b.dev', 'sifre1234')
    // The password alone does not sign in: the code screen comes first
    expect(await screen.findByRole('heading', { name: 'E-postanı kontrol et' })).toBeInTheDocument()
    expect(screen.getByText(/a\*\*\*\*@b\.dev adresine/)).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Doğrulama kodu'), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Doğrula' }))

    expect(verifyCode).toHaveBeenCalledWith('SIGN_IN', 'a@b.dev', '123456')
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

  it('tells a locked account to wait', async () => {
    login.mockRejectedValue(new ApiRequestError({ status: 429, message: 'Too many attempts', errors: {} }))
    renderAt('/login')

    await userEvent.type(screen.getByLabelText('E-posta'), 'a@b.dev')
    await userEvent.type(screen.getByLabelText('Şifre'), 'yanlis')
    await userEvent.click(screen.getByRole('button', { name: 'Giriş yap' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('15 dakika sonra')
  })

  it('signs up with name and surname, then the code', async () => {
    register.mockResolvedValue(sent)
    verifyCode.mockResolvedValue(undefined)
    renderAt('/login?mode=register')

    await userEvent.type(screen.getByLabelText('Ad'), 'Zeynep')
    await userEvent.type(screen.getByLabelText('Soyad'), 'Uğuz')
    await userEvent.type(screen.getByLabelText('E-posta'), 'z@b.dev')
    await userEvent.type(screen.getByLabelText('Şifre'), 'sifre1234')
    await userEvent.click(screen.getByRole('button', { name: 'Hesap oluştur' }))

    expect(register).toHaveBeenCalledWith({ firstName: 'Zeynep', lastName: 'Uğuz', email: 'z@b.dev', password: 'sifre1234' })
    await userEvent.type(await screen.findByLabelText('Doğrulama kodu'), '654321')
    await userEvent.click(screen.getByRole('button', { name: 'Doğrula' }))

    expect(verifyCode).toHaveBeenCalledWith('SIGN_UP', 'z@b.dev', '654321')
    expect(await screen.findByRole('heading', { name: 'Home' })).toBeInTheDocument()
  })

  it('explains a wrong code and keeps the code screen', async () => {
    login.mockResolvedValue(sent)
    verifyCode.mockRejectedValue(new ApiRequestError({ status: 400, message: 'Invalid or expired code', errors: {} }))
    renderAt('/login')

    await userEvent.type(screen.getByLabelText('E-posta'), 'a@b.dev')
    await userEvent.type(screen.getByLabelText('Şifre'), 'sifre1234')
    await userEvent.click(screen.getByRole('button', { name: 'Giriş yap' }))
    await userEvent.type(await screen.findByLabelText('Doğrulama kodu'), '000000')
    await userEvent.click(screen.getByRole('button', { name: 'Doğrula' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Kod hatalı ya da süresi dolmuş')
    expect(screen.getByRole('button', { name: /Kodu tekrar gönder \(\d+ sn\)/ })).toBeDisabled()
  })
})
