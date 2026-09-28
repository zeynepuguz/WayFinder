import { Camera, ChevronLeft, ChevronRight, ExternalLink, ImagePlus, Images, Trash2, X } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Link, useLocation, useNavigate } from 'react-router'
import { api } from '../api'
import { mediaUrl } from '../api/client'
import type { MyPhoto, PhotoStatus, PhotoTarget, UserPhoto } from '../api/types'
import { useAuth } from '../context/AuthContext'
import { formatDateTime } from '../lib/format'
import { locale, useT } from '../lib/i18n'
import { checkPhotoFile, currentPosition, PHOTO_ACCEPT, uploadErrorMessage } from '../lib/photos'
import { useAsync } from '../lib/useAsync'
import { HScroll } from './HScroll'
import { Alert, Sheet, Skeleton, Spinner, useToast } from './ui'

// Rendered on <body>: the place page's sheet is its own stacking context and would cover overlays
function Portal({ children }: { children: ReactNode }) {
  return typeof document === 'undefined' ? <>{children}</> : createPortal(children, document.body)
}

const photoDate = (iso: string) =>
  new Date(iso).toLocaleDateString(locale(), { day: 'numeric', month: 'long', year: 'numeric' })

// Only the first name is shown next to a photo
const firstName = (uploader: string) => uploader.trim().split(/\s+/)[0] ?? ''

/**
 * "Photos from our users" for a place (collage) or a district (compact strip):
 * the latest approved photos, an add button (account needed) and a Google Maps link for more.
 */
export function UserPhotosSection({ target, name, title, mapsUrl, variant = 'collage' }: {
  target: PhotoTarget
  // Place / district name, used in the alt texts
  name: string
  title: string
  mapsUrl: string
  variant?: 'collage' | 'strip'
}) {
  const t = useT()
  const { user } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const key = target.type === 'PLACE' ? `p${target.id}` : `d${target.city}/${target.district}`
  const { data, error, loading, reload } = useAsync(() => api.photos(target), [key])
  const [failed, setFailed] = useState<Set<number>>(() => new Set())
  const [open, setOpen] = useState<number | null>(null)
  const [adding, setAdding] = useState(false)

  const photos = (data ?? []).filter(p => !failed.has(p.id) && mediaUrl(p.thumbUrl) && mediaUrl(p.url)).slice(0, 10)
  const alt = () => `${name} — ${t('kullanıcı fotoğrafı', 'user photo')}`
  const onFailed = (id: number) => setFailed(current => new Set(current).add(id))

  function add() {
    // Adding a photo needs an account (no pass): guests log in and come back here
    if (!user) {
      navigate(`/login?next=${encodeURIComponent(location.pathname + location.search)}`)
      return
    }
    setAdding(true)
  }

  const addButton = (
    <button type="button" className="btn btn-sm btn-secondary" onClick={add}>
      <ImagePlus size={16} /> {t('Fotoğraf ekle', 'Add a photo')}
    </button>
  )

  const strip = variant === 'strip'
  let body: ReactNode
  if (loading && !data) {
    body = strip
      ? <div className="row" style={{ gap: 8 }}>{[0, 1, 2].map(i => <Skeleton key={i} height={96} width={96} radius={14} />)}</div>
      : <Skeleton height={200} radius={16} />
  } else if (error && !data) {
    body = (
      <p className="t-caption">
        {t('Fotoğraflar yüklenemedi.', 'Couldn’t load the photos.')}{' '}
        <button type="button" className="section-link" style={{ fontSize: 13 }} onClick={() => void reload()}>{t('Tekrar dene', 'Try again')}</button>
      </p>
    )
  } else if (photos.length === 0) {
    body = <p className="t-caption">{t('Henüz fotoğraf yok. İlk fotoğrafı sen ekle!', 'No photos yet. Be the first to add one!')}</p>
  } else if (strip) {
    body = (
      <HScroll className="h-scroll photo-strip" label={title}>
        {photos.map((p, i) => (
          <PhotoThumb key={p.id} photo={p} alt={alt()} onOpen={() => setOpen(i)} onError={() => onFailed(p.id)} />
        ))}
      </HScroll>
    )
  } else {
    body = (
      <div className={`photo-collage photo-collage-${Math.min(photos.length, 3)}`}>
        {photos.map((p, i) => (
          <PhotoThumb key={p.id} photo={p} alt={alt()} onOpen={() => setOpen(i)} onError={() => onFailed(p.id)} />
        ))}
      </div>
    )
  }

  return (
    <section className={`stack-sm user-photos ${strip ? 'user-photos-strip' : ''}`} aria-label={title}>
      <div className="section-head" style={{ alignItems: 'center' }}>
        <h2 className="t-headline">{title}</h2>
        {addButton}
      </div>
      {body}
      <a className="section-link" style={{ fontSize: 13, alignSelf: 'flex-start' }} href={mapsUrl} target="_blank" rel="noopener noreferrer">
        {t('Daha fazla fotoğraf için Google Haritalar’da gör', 'See more photos on Google Maps')} <ExternalLink size={12} />
      </a>

      {open != null && photos[open] && (
        <PhotoLightbox photos={photos} index={open} onIndex={setOpen} onClose={() => setOpen(null)} alt={alt} />
      )}
      <AddPhotoSheet open={adding} onClose={() => setAdding(false)} target={target} />
    </section>
  )
}

function PhotoThumb({ photo, alt, onOpen, onError }: { photo: UserPhoto; alt: string; onOpen: () => void; onError: () => void }) {
  return (
    <button type="button" className="photo-thumb" onClick={onOpen}>
      <img src={mediaUrl(photo.thumbUrl) ?? undefined} alt={alt} loading="lazy" decoding="async"
           width={photo.width || undefined} height={photo.height || undefined} onError={onError} />
    </button>
  )
}

const FOCUSABLE = 'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'

/** Full-screen viewer: arrows / swipe to move, Esc or the close button to leave; focus stays inside. */
export function PhotoLightbox({ photos, index, onIndex, onClose, alt }: {
  photos: UserPhoto[]
  index: number
  onIndex: (index: number) => void
  onClose: () => void
  alt: (index: number) => string
}) {
  const t = useT()
  const dialog = useRef<HTMLDivElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)
  const touchX = useRef<number | null>(null)
  const photo = photos[index]
  const count = photos.length

  const go = useCallback((step: -1 | 1) => {
    if (count > 1) onIndex((index + step + count) % count)
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

  const src = mediaUrl(photo.url)
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
        {src && <img className="lightbox-img" src={src} alt={alt(index)} decoding="async" />}
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
        <p className="lightbox-caption">
          {[firstName(photo.uploader), photoDate(photo.createdAt)].filter(Boolean).join(' · ')}
        </p>
      </div>
    </Portal>
  )
}

type UploadState = { step: 'choose' } | { step: 'uploading'; locating: boolean } | { step: 'done' }
  | { step: 'rejected'; message: string | null }

/** Rules, then "take a photo" / "choose from gallery"; the file is checked, sent unchanged and reviewed by the backend. */
export function AddPhotoSheet({ open, onClose, target }: { open: boolean; onClose: () => void; target: PhotoTarget }) {
  const t = useT()
  const [state, setState] = useState<UploadState>({ step: 'choose' })
  const [error, setError] = useState<string | null>(null)
  const camera = useRef<HTMLInputElement>(null)
  const gallery = useRef<HTMLInputElement>(null)
  const uploading = state.step === 'uploading'

  useEffect(() => {
    if (open) {
      setState({ step: 'choose' })
      setError(null)
    }
  }, [open])

  async function send(file: File | undefined) {
    if (!file) return
    setError(null)
    const problem = checkPhotoFile(file)
    if (problem) {
      setError(problem)
      return
    }
    setState({ step: 'uploading', locating: true })
    try {
      // Where the device is now; denied or unavailable -> sent without it
      const position = await currentPosition()
      setState({ step: 'uploading', locating: false })
      const result = await api.uploadPhoto(target, file, position)
      setState(result.status === 'REJECTED' ? { step: 'rejected', message: result.rejectMessage ?? null } : { step: 'done' })
    } catch (e) {
      setError(uploadErrorMessage(e))
      setState({ step: 'choose' })
    }
  }

  const onPick = (input: HTMLInputElement | null) => {
    const file = input?.files?.[0]
    // Choosing the same file again must fire change again
    if (input) input.value = ''
    void send(file)
  }

  return (
    <Portal>
      <Sheet open={open} onClose={uploading ? () => {} : onClose} label={t('Fotoğraf ekle', 'Add a photo')}>
        {state.step === 'rejected' ? (
          <div className="stack">
            <Alert tone="danger">
              <span>{state.message ?? t('Bu fotoğraf doğrulanamadı.', 'This photo couldn’t be verified.')}</span>
            </Alert>
            <button type="button" className="btn btn-primary btn-block" onClick={() => setState({ step: 'choose' })}>
              {t('Başka bir fotoğraf dene', 'Try another photo')}
            </button>
          </div>
        ) : state.step === 'done' ? (
          <div className="stack">
            <Alert tone="success">
              <span>{t('Fotoğrafın inceleniyor; uygun bulunursa birkaç dakika içinde görünecek.',
                'Your photo is being reviewed; if it’s suitable it will appear within a few minutes.')}</span>
            </Alert>
            <p className="t-caption">{t('Durumunu Profil › Fotoğraflarım bölümünde görebilirsin.', 'You can check its status under Profile › My photos.')}</p>
            <button type="button" className="btn btn-primary btn-block" onClick={onClose}>{t('Tamam', 'OK')}</button>
          </div>
        ) : (
          <div className="stack">
            <p className="ink-2">
              {t('Mekandayken çektiğin, mekanı gösteren fotoğraflar yayınlanır. Konumun sadece doğrulama için kullanılır, fotoğraftan silinir. Son 10 fotoğraf gösterilir.',
                'Photos you take on the spot that show the place get published. Your location is only used for verification and is removed from the photo. The latest 10 photos are shown.')}
            </p>
            {error && <Alert tone="danger"><span>{error}</span></Alert>}
            {uploading ? (
              <div className="row" role="status" style={{ justifyContent: 'center', padding: 12 }}>
                <Spinner />
                <span>{state.locating ? t('Konumun alınıyor…', 'Getting your location…') : t('Fotoğraf yükleniyor…', 'Uploading photo…')}</span>
              </div>
            ) : (
              <div className="row">
                <button type="button" className="btn btn-primary grow" onClick={() => camera.current?.click()}>
                  <Camera size={18} /> {t('Fotoğraf çek', 'Take a photo')}
                </button>
                <button type="button" className="btn btn-secondary grow" onClick={() => gallery.current?.click()}>
                  <Images size={18} /> {t('Galeriden seç', 'Choose from gallery')}
                </button>
              </div>
            )}
            <input ref={camera} type="file" accept={PHOTO_ACCEPT} capture="environment" hidden
                   data-testid="photo-camera-input" onChange={e => onPick(e.currentTarget)} />
            <input ref={gallery} type="file" accept={PHOTO_ACCEPT} hidden
                   data-testid="photo-gallery-input" onChange={e => onPick(e.currentTarget)} />
          </div>
        )}
      </Sheet>
    </Portal>
  )
}

const STATUS_BADGE: Record<PhotoStatus, { className: string; label: [string, string] }> = {
  PENDING: { className: 'badge-warning', label: ['İnceleniyor', 'In review'] },
  APPROVED: { className: 'badge-success', label: ['Yayında', 'Published'] },
  REJECTED: { className: 'badge-danger', label: ['Reddedildi', 'Rejected'] },
}
// While something is still in review the list is refreshed now and then (no websockets)
const POLL_MS = 20_000

/** Profile: my uploads with their review status; own photos can be deleted. */
export function MyPhotos() {
  const t = useT()
  const toast = useToast()
  const { data, setData, error, loading, reload } = useAsync(() => api.myPhotos(), [])
  const [confirm, setConfirm] = useState<MyPhoto | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [failed, setFailed] = useState<Set<number>>(() => new Set())
  const pending = data?.some(p => p.status === 'PENDING') ?? false

  useEffect(() => {
    if (!pending) return
    const timer = setInterval(() => void reload(), POLL_MS)
    return () => clearInterval(timer)
  }, [pending, reload])

  async function remove(photo: MyPhoto) {
    setDeleting(true)
    setDeleteError(null)
    try {
      await api.deletePhoto(photo.id)
      setData(current => current?.filter(p => p.id !== photo.id) ?? current)
      setConfirm(null)
      toast(t('Fotoğraf silindi', 'Photo deleted'))
    } catch (e) {
      setDeleteError(e instanceof Error && e.message ? e.message : t('Fotoğraf silinemedi', 'Couldn’t delete the photo'))
    } finally {
      setDeleting(false)
    }
  }

  return (
    <section className="stack-sm" aria-label={t('Fotoğraflarım', 'My photos')}>
      <h2 className="t-headline">{t('Fotoğraflarım', 'My photos')}</h2>
      {loading && !data ? (
        <Skeleton height={72} radius={20} />
      ) : error && !data ? (
        <p className="t-caption">
          {t('Fotoğrafların yüklenemedi.', 'Couldn’t load your photos.')}{' '}
          <button type="button" className="section-link" style={{ fontSize: 13 }} onClick={() => void reload()}>{t('Tekrar dene', 'Try again')}</button>
        </p>
      ) : !data?.length ? (
        <p className="t-caption">{t('Henüz fotoğraf eklemedin. Bir mekanın sayfasından fotoğraf ekleyebilirsin.',
          'You haven’t added any photos yet. You can add one from a place’s page.')}</p>
      ) : (
        <div className="list-group">
          {data.map(photo => {
            const badge = STATUS_BADGE[photo.status]
            const thumb = mediaUrl(photo.thumbUrl)
            return (
              <div key={photo.id} className="list-item my-photo">
                <span className="my-photo-thumb">
                  {thumb && !failed.has(photo.id) && (
                    <img src={thumb} alt="" loading="lazy" decoding="async" onError={() => setFailed(c => new Set(c).add(photo.id))} />
                  )}
                </span>
                <span className="grow stack-sm" style={{ gap: 4, minWidth: 0 }}>
                  {photo.target.type === 'PLACE' && photo.target.id != null
                    ? <Link to={`/places/${photo.target.id}`} className="my-photo-name">{photo.target.name}</Link>
                    : <span className="my-photo-name">{photo.target.name}</span>}
                  <span className="row wrap" style={{ gap: 6 }}>
                    <span className={`badge ${badge.className}`}>{t(...badge.label)}</span>
                    <span className="t-caption">{formatDateTime(photo.createdAt)}</span>
                  </span>
                  {photo.status === 'REJECTED' && photo.rejectMessage && <span className="t-caption">{photo.rejectMessage}</span>}
                </span>
                <button type="button" className="icon-btn icon-btn-plain" aria-label={t('Fotoğrafı sil', 'Delete photo')}
                        onClick={() => { setDeleteError(null); setConfirm(photo) }}>
                  <Trash2 size={18} />
                </button>
              </div>
            )
          })}
        </div>
      )}

      <Sheet open={confirm != null} onClose={() => setConfirm(null)} label={t('Fotoğraf silinsin mi?', 'Delete this photo?')}>
        <div className="stack">
          <p className="ink-2">{t('Fotoğraf kalıcı olarak silinir ve artık gösterilmez.', 'The photo is deleted permanently and no longer shown.')}</p>
          {deleteError && <Alert tone="danger"><span>{deleteError}</span></Alert>}
          <div className="row">
            <button type="button" className="btn btn-secondary grow" onClick={() => setConfirm(null)}>{t('Vazgeç', 'Cancel')}</button>
            <button type="button" className="btn btn-danger grow" disabled={deleting} onClick={() => confirm && void remove(confirm)}>
              {deleting ? <Spinner /> : t('Sil', 'Delete')}
            </button>
          </div>
        </div>
      </Sheet>
    </section>
  )
}
