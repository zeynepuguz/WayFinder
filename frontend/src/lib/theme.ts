import { useEffect, useState } from 'react'

// Light or dark look: the phone's setting ("system", the default) or the user's choice in Profile.
// The resolved look is written to <html data-theme="light|dark">; styles.css and the map follow it.
export type ThemePref = 'system' | 'light' | 'dark'

const KEY = 'nomi.theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'
const EVENT = 'nomi-theme'

export function themePref(): ThemePref {
  try {
    const saved = localStorage.getItem(KEY)
    return saved === 'light' || saved === 'dark' ? saved : 'system'
  } catch {
    return 'system'
  }
}

export function isDark(pref: ThemePref = themePref()): boolean {
  if (pref !== 'system') return pref === 'dark'
  return window.matchMedia?.(DARK_QUERY).matches ?? false
}

function apply() {
  const dark = isDark()
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
  window.dispatchEvent(new CustomEvent(EVENT, { detail: dark }))
}

export function setThemePref(pref: ThemePref) {
  try {
    if (pref === 'system') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, pref)
  } catch {
    // private mode: the choice lasts until the app closes
  }
  apply()
}

/** Called once at start: applies the look and follows the phone when it switches between light and dark. */
export function initTheme() {
  apply()
  window.matchMedia?.(DARK_QUERY).addEventListener('change', () => { if (themePref() === 'system') apply() })
}

/** Runs on every switch between the light and the dark look. */
export function onThemeChange(listener: (dark: boolean) => void) {
  window.addEventListener(EVENT, e => listener((e as CustomEvent<boolean>).detail))
}

/** True while the app shows its dark look; updates when the phone or the user switches. */
export function useIsDark(): boolean {
  const [dark, setDark] = useState(() => isDark())
  useEffect(() => {
    const onChange = (e: Event) => setDark((e as CustomEvent<boolean>).detail)
    window.addEventListener(EVENT, onChange)
    const query = window.matchMedia?.(DARK_QUERY)
    const onSystem = () => setDark(isDark())
    query?.addEventListener('change', onSystem)
    return () => {
      window.removeEventListener(EVENT, onChange)
      query?.removeEventListener('change', onSystem)
    }
  }, [])
  return dark
}
