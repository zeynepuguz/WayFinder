import { ChevronLeft, ChevronRight } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type CSSProperties, type ReactNode, type RefObject } from 'react'
import { useT } from '../lib/i18n'

// A mouse press has to move this far before it counts as a drag (and the following click is swallowed)
const DRAG_THRESHOLD = 6
const LINE_HEIGHT = 16

/**
 * How far a wheel event should move a horizontal row, or null to leave the event to the page.
 * Trackpads that already scroll sideways and rows that are at the end in that direction keep native behaviour,
 * so the page still scrolls vertically once a row is done.
 */
export function wheelToHorizontal(
  event: { deltaX: number; deltaY: number; deltaMode: number },
  row: { scrollLeft: number; clientWidth: number; scrollWidth: number },
): number | null {
  const { deltaX, deltaY, deltaMode } = event
  if (row.scrollWidth - row.clientWidth <= 1) return null
  if (deltaY === 0 || Math.abs(deltaX) >= Math.abs(deltaY)) return null
  const delta = deltaMode === 1 ? deltaY * LINE_HEIGHT : deltaMode === 2 ? deltaY * row.clientWidth : deltaY
  const maxLeft = row.scrollWidth - row.clientWidth
  if (delta < 0 && row.scrollLeft <= 0) return null
  if (delta > 0 && row.scrollLeft >= maxLeft - 1) return null
  return delta
}

/**
 * Makes a horizontally scrolling row usable with a mouse: the vertical wheel scrolls it sideways,
 * press-and-drag scrolls it (without firing a click on the card under the mouse) and the
 * returned flags tell whether there is more content to the left / right. Touch keeps native swiping.
 */
function useHorizontalScroll(ref: RefObject<HTMLElement | null>) {
  const [edges, setEdges] = useState({ left: false, right: false })

  const measure = useCallback(() => {
    const el = ref.current
    if (!el) return
    const max = el.scrollWidth - el.clientWidth
    const left = max > 1 && el.scrollLeft > 1
    const right = max > 1 && el.scrollLeft < max - 1
    setEdges(current => (current.left === left && current.right === right ? current : { left, right }))
  }, [ref])

  useEffect(() => {
    const el = ref.current
    if (!el) return

    // Non-passive, so the page does not scroll while the row takes the wheel
    const onWheel = (e: WheelEvent) => {
      const delta = wheelToHorizontal(e, el)
      if (delta == null) return
      e.preventDefault()
      el.scrollLeft += delta
    }

    let start: { x: number; scrollLeft: number } | null = null
    let dragging = false
    const swallowClick = (e: MouseEvent) => {
      e.preventDefault()
      e.stopPropagation()
    }
    const onMove = (e: MouseEvent) => {
      if (!start) return
      const dx = e.clientX - start.x
      if (!dragging && Math.abs(dx) < DRAG_THRESHOLD) return
      dragging = true
      el.classList.add('is-dragging')
      el.scrollLeft = start.scrollLeft - dx
    }
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      if (dragging) {
        // The click that ends a drag belongs to the drag, not to the card it happens to end on
        el.addEventListener('click', swallowClick, { capture: true, once: true })
        setTimeout(() => el.removeEventListener('click', swallowClick, { capture: true }), 0)
        el.classList.remove('is-dragging')
      }
      start = null
      dragging = false
    }
    // Touch devices send a mousedown only after a tap (no movement), so swiping is untouched
    const onDown = (e: MouseEvent) => {
      if (e.button !== 0 || el.scrollWidth - el.clientWidth <= 1) return
      start = { x: e.clientX, scrollLeft: el.scrollLeft }
      dragging = false
      window.addEventListener('mousemove', onMove)
      window.addEventListener('mouseup', onUp)
    }
    // Links and images would otherwise start a native drag-and-drop instead of scrolling
    const onDragStart = (e: DragEvent) => e.preventDefault()

    el.addEventListener('wheel', onWheel, { passive: false })
    el.addEventListener('mousedown', onDown)
    el.addEventListener('dragstart', onDragStart)
    el.addEventListener('scroll', measure, { passive: true })
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure)
    observer?.observe(el)
    measure()
    return () => {
      el.removeEventListener('wheel', onWheel)
      el.removeEventListener('mousedown', onDown)
      el.removeEventListener('dragstart', onDragStart)
      el.removeEventListener('scroll', measure)
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      observer?.disconnect()
    }
  }, [ref, measure])

  const scrollPage = useCallback((direction: -1 | 1) => {
    const el = ref.current
    if (!el) return
    const amount = Math.max(el.clientWidth * 0.8, 120) * direction
    if (typeof el.scrollBy === 'function') el.scrollBy({ left: amount, behavior: 'smooth' })
    else el.scrollLeft += amount
  }, [ref])

  return { canScrollLeft: edges.left, canScrollRight: edges.right, scrollPage, measure }
}

/**
 * Horizontal row (carousel or chip row) with mouse support and small arrow buttons on desktop.
 * `className` styles the scrolling element itself (e.g. "h-scroll", "h-scroll chip-scroll", "suggestions").
 */
export function HScroll({ className = 'h-scroll', style, children, label }: {
  className?: string
  style?: CSSProperties
  children: ReactNode
  label?: string
}) {
  const t = useT()
  const ref = useRef<HTMLDivElement>(null)
  const { canScrollLeft, canScrollRight, scrollPage, measure } = useHorizontalScroll(ref)

  // Content arrives later (loading → cards): re-check whether there is anything to scroll
  useEffect(measure, [children, measure])

  return (
    <div className="hscroll">
      <div ref={ref} className={className} style={style} role={label ? 'group' : undefined} aria-label={label}>
        {children}
      </div>
      {canScrollLeft && (
        <button type="button" className="hscroll-arrow hscroll-arrow-left" tabIndex={-1}
                aria-label={t('Sola kaydır', 'Scroll left')} onClick={() => scrollPage(-1)}>
          <ChevronLeft size={18} />
        </button>
      )}
      {canScrollRight && (
        <button type="button" className="hscroll-arrow hscroll-arrow-right" tabIndex={-1}
                aria-label={t('Sağa kaydır', 'Scroll right')} onClick={() => scrollPage(1)}>
          <ChevronRight size={18} />
        </button>
      )}
    </div>
  )
}
