import { useEffect, useLayoutEffect, useRef } from 'react'
import { useLocation, useNavigationType } from 'react-router'

// Scroll position per history entry (location.key), so going back lands where the user was
const positions = new Map<string, number>()
const MAX_POSITIONS = 100
// A screen whose data comes a moment later (not cached yet) grows after the first frame: keep trying this long
const RESTORE_FOR_MS = 1500

/**
 * Back (POP): the screen scrolls to where the user left it. A new screen (PUSH) starts at the top. A replaced
 * entry (filters in the URL, the assistant's ?c=) keeps its position.
 */
export function useScrollMemory() {
  const location = useLocation()
  const navigationType = useNavigationType()
  const key = useRef(location.key)
  key.current = location.key

  // Remember the position of the current entry as the user scrolls
  useEffect(() => {
    let frame = 0
    const onScroll = () => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(() => {
        positions.delete(key.current)
        positions.set(key.current, window.scrollY)
        if (positions.size > MAX_POSITIONS) positions.delete(positions.keys().next().value as string)
      })
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('scroll', onScroll)
    }
  }, [])

  useLayoutEffect(() => {
    if (navigationType === 'PUSH') {
      window.scrollTo(0, 0)
      return
    }
    const target = navigationType === 'POP' ? positions.get(location.key) : undefined
    if (target === undefined || target <= 0) return
    // The page may still be growing (data, images): try until it is tall enough, unless the user scrolls first
    const started = performance.now()
    let frame = 0
    let userMoved = false
    const stop = () => { userMoved = true }
    window.addEventListener('touchstart', stop, { passive: true, once: true })
    window.addEventListener('wheel', stop, { passive: true, once: true })
    const attempt = () => {
      if (userMoved) return
      window.scrollTo(0, target)
      if (Math.abs(window.scrollY - target) > 2 && performance.now() - started < RESTORE_FOR_MS) {
        frame = requestAnimationFrame(attempt)
      }
    }
    attempt()
    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('touchstart', stop)
      window.removeEventListener('wheel', stop)
    }
  }, [location.key])
}
