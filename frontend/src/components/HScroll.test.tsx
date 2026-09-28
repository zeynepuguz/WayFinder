import { fireEvent, render, screen } from '@testing-library/react'
import { HScroll, wheelToHorizontal } from './HScroll'

const row = (scrollLeft: number) => ({ scrollLeft, clientWidth: 300, scrollWidth: 900 })

describe('wheelToHorizontal', () => {
  it('turns a vertical wheel into sideways scrolling while the row can move', () => {
    expect(wheelToHorizontal({ deltaX: 0, deltaY: 100, deltaMode: 0 }, row(0))).toBe(100)
    expect(wheelToHorizontal({ deltaX: 0, deltaY: -100, deltaMode: 0 }, row(200))).toBe(-100)
    expect(wheelToHorizontal({ deltaX: 0, deltaY: 3, deltaMode: 1 }, row(0))).toBe(48)
  })

  it('leaves the page scrolling at the ends, for trackpad swipes and rows that fit', () => {
    expect(wheelToHorizontal({ deltaX: 0, deltaY: -100, deltaMode: 0 }, row(0))).toBeNull()
    expect(wheelToHorizontal({ deltaX: 0, deltaY: 100, deltaMode: 0 }, row(600))).toBeNull()
    expect(wheelToHorizontal({ deltaX: 80, deltaY: 10, deltaMode: 0 }, row(100))).toBeNull()
    expect(wheelToHorizontal({ deltaX: 0, deltaY: 100, deltaMode: 0 }, { scrollLeft: 0, clientWidth: 300, scrollWidth: 300 })).toBeNull()
  })
})

describe('HScroll', () => {
  function renderRow(onClick: () => void) {
    render(<HScroll label="Kartlar"><button onClick={onClick}>Kart</button></HScroll>)
    const el = screen.getByRole('group', { name: 'Kartlar' })
    Object.defineProperty(el, 'clientWidth', { configurable: true, value: 300 })
    Object.defineProperty(el, 'scrollWidth', { configurable: true, value: 900 })
    return el
  }

  it('scrolls sideways with the mouse wheel and blocks the page scroll meanwhile', () => {
    const el = renderRow(() => {})
    const notPrevented = fireEvent.wheel(el, { deltaY: 120 })
    expect(notPrevented).toBe(false)
    expect(el.scrollLeft).toBe(120)
  })

  it('drags with the mouse without clicking the card the drag ends on', () => {
    const onClick = vi.fn()
    const el = renderRow(onClick)
    const card = screen.getByRole('button', { name: 'Kart' })

    fireEvent.mouseDown(card, { button: 0, clientX: 250 })
    fireEvent.mouseMove(window, { clientX: 100 })
    fireEvent.mouseUp(window, { clientX: 100 })
    fireEvent.click(card)
    expect(el.scrollLeft).toBe(150)
    expect(onClick).not.toHaveBeenCalled()

    // a plain click (no movement) still works
    fireEvent.mouseDown(card, { button: 0, clientX: 100 })
    fireEvent.mouseUp(window, { clientX: 101 })
    fireEvent.click(card)
    expect(onClick).toHaveBeenCalledTimes(1)
  })
})
