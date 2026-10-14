import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RouteFeedback } from './RouteFeedback'
import { ToastProvider } from './ui'

const routeFeedback = vi.fn()
const setRouteFeedback = vi.fn()
const removeRouteFeedback = vi.fn()

vi.mock('../api', () => ({
  api: {
    routeFeedback: (id: number) => routeFeedback(id),
    setRouteFeedback: (id: number, liked: boolean) => setRouteFeedback(id, liked),
    removeRouteFeedback: (id: number) => removeRouteFeedback(id),
  },
}))

const renderIt = () => render(<ToastProvider><RouteFeedback routeId={3} /></ToastProvider>)

describe('RouteFeedback', () => {
  beforeEach(() => {
    routeFeedback.mockReset()
    setRouteFeedback.mockReset()
    removeRouteFeedback.mockReset()
  })

  it('likes the route and says it will be suggested to others', async () => {
    routeFeedback.mockResolvedValue({ liked: null })
    setRouteFeedback.mockResolvedValue({ liked: true })
    renderIt()

    await userEvent.click(await screen.findByRole('button', { name: 'Beğendim' }))

    expect(setRouteFeedback).toHaveBeenCalledWith(3, true)
    expect(await screen.findByText(/benzer bir gün planlayanlara da bu rota önerilecek/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Beğendim' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('takes the answer back when it is tapped again', async () => {
    routeFeedback.mockResolvedValue({ liked: false })
    removeRouteFeedback.mockResolvedValue({ liked: null })
    renderIt()

    await userEvent.click(await screen.findByRole('button', { name: 'Beğenmedim' }))

    expect(removeRouteFeedback).toHaveBeenCalledWith(3)
    expect(await screen.findByRole('button', { name: 'Beğenmedim' })).toHaveAttribute('aria-pressed', 'false')
  })
})
