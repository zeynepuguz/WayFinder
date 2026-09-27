import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

// Turkish for locals, English for tourists ("For tourists"). Texts live next to their use:
//   const t = useT();  t('Kaydedilenler', 'Saved')
// so a screen can never show a string that exists in only one language.
export type Lang = 'tr' | 'en'

const STORAGE_KEY = 'nomi.lang'

function detect(): Lang {
  try {
    const fromUrl = new URLSearchParams(window.location.search).get('lang')
    if (fromUrl === 'tr' || fromUrl === 'en') {
      // Guide pages link to /?lang=en: keep that choice on the next screens too
      localStorage.setItem(STORAGE_KEY, fromUrl)
      return fromUrl
    }
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved === 'tr' || saved === 'en') return saved
  } catch {
    // storage blocked (private mode): fall back to the device language
  }
  // A phone set to English is most likely a visitor
  return (navigator.language ?? 'tr').toLowerCase().startsWith('tr') ? 'tr' : 'en'
}

let current: Lang = detect()
if (typeof document !== 'undefined') document.documentElement.lang = current

/** Language outside React (API client, formatters). The app remounts when it changes. */
export function currentLang(): Lang {
  return current
}

/** For code outside components; inside components prefer useT(). */
export function tr(turkish: string, english: string): string {
  return current === 'en' ? english : turkish
}

/** BCP 47 locale for dates and numbers */
export function locale(): string {
  return current === 'en' ? 'en-GB' : 'tr-TR'
}

/** A record whose values follow the current language, e.g. CATEGORY_LABELS.CAFE */
export function bilingual<K extends string>(entries: Record<K, [string, string]>): Record<K, string> {
  const result = {} as Record<K, string>
  for (const key of Object.keys(entries) as K[]) {
    Object.defineProperty(result, key, { enumerable: true, get: () => tr(entries[key][0], entries[key][1]) })
  }
  return result
}

interface LanguageState {
  lang: Lang
  setLang: (lang: Lang) => void
}

const LanguageContext = createContext<LanguageState>({ lang: current, setLang: () => {} })

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(current)

  const setLang = useCallback((next: Lang) => {
    current = next
    document.documentElement.lang = next
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // not remembered, still switched for this visit
    }
    setLangState(next)
  }, [])

  const value = useMemo(() => ({ lang, setLang }), [lang, setLang])
  // key: remount everything so every text and every API response (Accept-Language) follows the new language
  return <LanguageContext.Provider value={value}><div key={lang} style={{ display: 'contents' }}>{children}</div></LanguageContext.Provider>
}

export function useLang(): LanguageState {
  return useContext(LanguageContext)
}

export function useT(): (turkish: string, english: string) => string {
  const { lang } = useLang()
  return useCallback((turkish: string, english: string) => (lang === 'en' ? english : turkish), [lang])
}
