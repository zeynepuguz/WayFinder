import { initTheme, isDark, setThemePref, themePref } from './theme'

describe('theme', () => {
  afterEach(() => setThemePref('system'))

  it('follows the phone by default (light when the phone is light)', () => {
    initTheme()
    expect(themePref()).toBe('system')
    expect(document.documentElement.dataset.theme).toBe('light')
  })

  it('a choice in Profile wins over the phone and is remembered', () => {
    setThemePref('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(themePref()).toBe('dark')
    expect(isDark()).toBe(true)

    setThemePref('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(isDark()).toBe(false)
  })

  it('back to "Sistem" forgets the choice', () => {
    setThemePref('dark')
    setThemePref('system')
    expect(localStorage.getItem('nomi.theme')).toBeNull()
    expect(document.documentElement.dataset.theme).toBe('light')
  })
})
