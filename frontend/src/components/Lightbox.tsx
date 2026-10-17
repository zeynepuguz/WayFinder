import { ChevronLeft, ChevronRight, X } from 'lucide-react'
import { useCallback, useEffect, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useT } from '../lib/i18n'

// Rendered on <body>: the place page's sheet is its own stacking context and would cover overlays
export function Portal({ children }: { children: ReactNode }) {
  return typeof document === 'undefined' ? <>{children}</> : createPortal(children, document.body)
}

const FOCUSABLE = 'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'

/**
 * Full-screen photo viewer: arrows / swipe to move (more than one photo), Esc, the close button or a tap beside the
 * photo to leave; focus stays inside and goes back to what opened it. The caption (credit, uploader) sits below.
 */
export function Lightbox({ src, alt, index = 0, count = 1, onIndex, onClose, caption }: {
  src: string | null
  alt: string
  index?: number
  count?: number
  onIndex?: (index: number) => void
  onClose: () => void
  caption?: ReactNode
}) {
  const t = useT()
  const dialog = useRef<HTMLDivElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)
  const touchX = useRef<number | null>(null)

  const go = useCallback((step: -1 | 1) => {
    if (count > 1) onIndex?.((index + step + count) % count)
  }, [count, index, onIndex])

  // Focus moves into the viewer and back to the photo that opened it
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    closeButton.current?.focus()
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = overflow
      previous?.focus?.()
    }
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onClose()
      } else if (e.key === 'ArrowLeft') go(-1)
      else if (e.key === 'ArrowRight') go(1)
      else if (e.key === 'Tab' && dialog.current) {
        const items = Array.from(dialog.current.querySelectorAll<HTMLElement>(FOCUSABLE))
        if (!items.length) return
        const first = items[0]
        const last = items[items.length - 1]
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault()
          last.focus()
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault()
          first.focus()
        } else if (!dialog.current.contains(document.activeElement)) {
          e.preventDefault()
          first.focus()
        }
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [go, onClose])

  return (
    <Portal>
      <div className="lightbox" role="dialog" aria-modal="true" aria-label={t('Fotoğraf', 'Photo')} ref={dialog}
           onClick={e => e.target === e.currentTarget && onClose()}
           onTouchStart={e => { touchX.current = e.touches[0]?.clientX ?? null }}
           onTouchEnd={e => {
             const start = touchX.current
             const end = e.changedTouches[0]?.clientX
             touchX.current = null
             if (start == null || end == null || Math.abs(end - start) < 40) return
             go(end < start ? 1 : -1)
           }}>
        <div className="lightbox-bar">
          <span className="lightbox-count" aria-live="polite">{count > 1 ? `${index + 1} / ${count}` : ''}</span>
          <button ref={closeButton} type="button" className="icon-btn icon-btn-glass" aria-label={t('Kapat', 'Close')} onClick={onClose}>
            <X size={20} />
          </button>
        </div>
        {src && <img className="lightbox-img" src={src} alt={alt} decoding="async" />}
        {count > 1 && (
          <>
            <button type="button" className="icon-btn icon-btn-glass lightbox-nav lightbox-prev" aria-label={t('Önceki fotoğraf', 'Previous photo')} onClick={() => go(-1)}>
              <ChevronLeft size={22} />
            </button>
            <button type="button" className="icon-btn icon-btn-glass lightbox-nav lightbox-next" aria-label={t('Sonraki fotoğraf', 'Next photo')} onClick={() => go(1)}>
              <ChevronRight size={22} />
            </button>
          </>
        )}
        {caption && <div className="lightbox-caption">{caption}</div>}
      </div>
    </Portal>
  )
}
