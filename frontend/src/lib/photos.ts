import { ApiRequestError } from '../api/client'
import type { DevicePosition } from '../api/types'
import { tr } from './i18n'

const MAX_PHOTO_BYTES = 12 * 1024 * 1024
export const PHOTO_ACCEPT = 'image/jpeg,image/png,image/webp'
const ALLOWED_TYPES = PHOTO_ACCEPT.split(',')
const ALLOWED_EXTENSIONS = /\.(jpe?g|png|webp)$/i
const HEIC = /\.(heic|heif)$/i

/**
 * Checks a chosen file before uploading it; returns a message for the user, or null when it may be sent.
 * The file itself is sent unchanged (no resizing): the backend reads the original EXIF position.
 */
export function checkPhotoFile(file: File): string | null {
  if (file.type === 'image/heic' || file.type === 'image/heif' || HEIC.test(file.name)) {
    return tr('HEIC biçimindeki fotoğraflar desteklenmiyor. Lütfen JPEG olarak seç (iPhone: Ayarlar › Kamera › Biçimler › En Uyumlu).',
      'HEIC photos aren’t supported. Please choose a JPEG (iPhone: Settings › Camera › Formats › Most Compatible).')
  }
  // Some Android pickers give no type: the file name decides then
  const typeOk = file.type ? ALLOWED_TYPES.includes(file.type.toLowerCase()) : ALLOWED_EXTENSIONS.test(file.name)
  if (!typeOk) {
    return tr('Bu dosya türü desteklenmiyor. JPEG, PNG ya da WebP bir fotoğraf seç.',
      'This file type isn’t supported. Choose a JPEG, PNG or WebP photo.')
  }
  if (file.size > MAX_PHOTO_BYTES) {
    return tr('Fotoğraf çok büyük. En fazla 12 MB olabilir.', 'The photo is too large. The limit is 12 MB.')
  }
  if (file.size === 0) {
    return tr('Fotoğraf okunamadı. Başka bir fotoğraf seç.', 'Couldn’t read the photo. Choose another one.')
  }
  return null
}

/**
 * The device position right now (high accuracy, up to ~10 s), or null when it is denied / unavailable.
 * Without it the upload still goes ahead: the backend may use the photo's own GPS data.
 */
export function currentPosition(timeout = 10_000): Promise<DevicePosition | null> {
  return new Promise(resolve => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      resolve(null)
      return
    }
    try {
      navigator.geolocation.getCurrentPosition(
        p => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude, accuracy: p.coords.accuracy }),
        () => resolve(null),
        { enableHighAccuracy: true, timeout, maximumAge: 30_000 },
      )
    } catch {
      resolve(null)
    }
  })
}

/** Friendly text for a failed upload; the backend's own (localized) message where it has one. */
export function uploadErrorMessage(error: unknown): string {
  if (error instanceof ApiRequestError) {
    switch (error.status) {
      case 401: return tr('Fotoğraf eklemek için giriş yapmalısın.', 'Please sign in to add a photo.')
      case 413: return tr('Fotoğraf çok büyük. En fazla 12 MB olabilir.', 'The photo is too large. The limit is 12 MB.')
      case 415: return tr('Bu dosya türü desteklenmiyor. JPEG, PNG ya da WebP bir fotoğraf seç.',
        'This file type isn’t supported. Choose a JPEG, PNG or WebP photo.')
      case 429: return error.message || tr('Bugünlük fotoğraf ekleme sınırına ulaştın.', 'You’ve reached today’s photo limit.')
      default: if (error.message) return error.message
    }
  }
  return tr('Fotoğraf yüklenemedi. Lütfen tekrar dene.', 'Couldn’t upload the photo. Please try again.')
}
