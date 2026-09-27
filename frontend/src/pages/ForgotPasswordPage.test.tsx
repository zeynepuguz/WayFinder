import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { ApiRequestError } from '../api/client'
import { ForgotPasswordPage } from './ForgotPasswordPage'

const resetPassword = vi.fn()
const forgotPassword = vi.fn()

vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ resetPassword }) }))
vi.mock('../api', () => ({ api: { forgotPassword: (email: string) => forgotPassword(email) } }))

function renderAt(url: string) {
  return render(
    <MemoryRouter initialEntries={[url]}>
      <Routes>
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/assistant" element={<h1>Assistant</h1>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ForgotPasswordPage', () => {
  beforeEach(() => {
    resetPassword.mockReset()
    forgotPassword.mockReset().mockResolvedValue(undefined)
  })

  it('sends a code, then sets the new password and continues', async () => {
    resetPassword.mockResolvedValue(undefined)
    renderAt('/forgot-password?next=%2Fassistant&email=a%40b.dev')

    expect(screen.getByLabelText('E-posta')).toHaveValue('a@b.dev')
    await userEvent.click(screen.getByRole('button', { name: 'Kod gönder' }))
    expect(forgotPassword).toHaveBeenCalledWith('a@b.dev')

    await userEvent.type(screen.getByLabelText('Doğrulama kodu'), '12a3456')
    await userEvent.type(screen.getByLabelText('Yeni şifre'), 'yeni-sifre-1')
    await userEvent.click(screen.getByRole('button', { name: 'Şifremi yenile' }))

    expect(resetPassword).toHaveBeenCalledWith('a@b.dev', '123456', 'yeni-sifre-1')
    expect(await screen.findByRole('heading', { name: 'Assistant' })).toBeInTheDocument()
  })

  it('explains a wrong or expired code', async () => {
    resetPassword.mockRejectedValue(new ApiRequestError({ status: 400, message: 'Invalid or expired code', errors: {} }))
    renderAt('/forgot-password?email=a%40b.dev')

    await userEvent.click(screen.getByRole('button', { name: 'Kod gönder' }))
    await userEvent.type(screen.getByLabelText('Doğrulama kodu'), '000000')
    await userEvent.type(screen.getByLabelText('Yeni şifre'), 'yeni-sifre-1')
    await userEvent.click(screen.getByRole('button', { name: 'Şifremi yenile' }))

    expect(await screen.findByText(/Kod hatalı ya da süresi dolmuş/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Kodu tekrar gönder \(\d+ sn\)/ })).toBeDisabled()
  })
})
