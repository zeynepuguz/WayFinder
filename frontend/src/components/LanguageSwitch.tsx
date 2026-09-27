import { Languages } from 'lucide-react'
import { useLang } from '../lib/i18n'

// "For tourists": switches the whole app (and the backend's answers) between Turkish and English
export function LanguageSwitch({ compact = false }: { compact?: boolean }) {
  const { lang, setLang } = useLang()
  const toEnglish = lang === 'tr'
  return (
    <button
      type="button"
      className={`lang-switch ${compact ? 'compact' : ''}`}
      lang={toEnglish ? 'en' : 'tr'}
      onClick={() => setLang(toEnglish ? 'en' : 'tr')}
      aria-label={toEnglish ? 'For tourists: switch to English' : 'Türkçeye geç'}
    >
      <Languages size={16} />
      {toEnglish ? (compact ? 'EN' : 'For tourists · English') : (compact ? 'TR' : 'Türkçe')}
    </button>
  )
}
