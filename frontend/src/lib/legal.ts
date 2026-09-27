// Google Play requires a public privacy policy. Host the texts and set their URLs at build time.
export const LEGAL_LINKS = {
  privacy: import.meta.env.VITE_PRIVACY_URL as string | undefined,
  terms: import.meta.env.VITE_TERMS_URL as string | undefined,
  support: import.meta.env.VITE_SUPPORT_EMAIL as string | undefined,
}
