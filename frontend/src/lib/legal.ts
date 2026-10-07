// About and legal pages (pages/LegalPage); Google Play needs the privacy policy at a public URL: https://<domain>/gizlilik
export type LegalDoc = 'about' | 'terms' | 'privacy'

export const LEGAL_PATHS: Record<LegalDoc, string> = { about: '/iletisim', terms: '/kosullar', privacy: '/gizlilik' }

// Shown on the about / legal pages and under Profil > Destek; empty = not set yet
export const SUPPORT_EMAIL = (import.meta.env.VITE_SUPPORT_EMAIL as string | undefined) || ''
