import { ArrowRight, CloudRain, Coffee, MapPin, Route, Sparkles, Wallet, type LucideIcon } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { BrandMark } from '../components/visuals'
import { LanguageSwitch } from '../components/LanguageSwitch'
import { useT } from '../lib/i18n'

const ONBOARDED_KEY = 'nomi.onboarded'

export function hasOnboarded(): boolean {
  try {
    return localStorage.getItem(ONBOARDED_KEY) === '1'
  } catch {
    return true
  }
}

function markOnboarded() {
  try {
    localStorage.setItem(ONBOARDED_KEY, '1')
  } catch {
    // ignore
  }
}

const slides = (t: (turkish: string, english: string) => string): {
  title: string
  text: string
  icon: LucideIcon
  gradient: string
  bubbles: { icon: LucideIcon; text: string; style: React.CSSProperties }[]
}[] => [
  {
    title: t('Şehri senin için araştıran asistan', 'An assistant that explores the city for you'),
    text: t('Nerede kahvaltı, nerede kahve, hangi sırayla? Nomi bütçene, zamanına ve zevkine göre gününü planlar.', 'Where to have breakfast, where to get coffee, in what order? Nomi plans your day around your budget, time and taste.'),
    icon: Sparkles,
    gradient: 'linear-gradient(145deg, #ff8a5c, #e8452a)',
    bubbles: [
      { icon: Coffee, text: t('Moda’da nitelikli kahve', 'Specialty coffee in Moda'), style: { top: '18%', left: '8%' } },
      { icon: Wallet, text: t('2 kişi · 700 TL', '2 people · 700 TL'), style: { bottom: '20%', right: '8%' } },
    ],
  },
  {
    title: t('Gerçek verilerle, uygulanabilir rotalar', 'Doable routes built on real data'),
    text: t('Mekanların açık olduğu saatler, aradaki yürüme mesafesi ve kişi başı harcama hep hesapta.', 'Opening hours, walking distances and cost per person are always taken into account.'),
    icon: Route,
    gradient: 'linear-gradient(145deg, #1f8a96, #0b4f63)',
    bubbles: [
      { icon: MapPin, text: t('Sonraki durak 350 m', 'Next stop 350 m'), style: { top: '20%', right: '8%' } },
      { icon: Route, text: t('09:30 → Kahvaltı', '09:30 → Breakfast'), style: { bottom: '22%', left: '8%' } },
    ],
  },
  {
    title: t('Gezerken de yanında', 'With you on the go'),
    text: t('“Çok yorulduk”, “yağmur başladı” de; Nomi rotanı bulunduğun yerden yeniden düzenlesin.', 'Say “we’re tired” or “it started raining” and Nomi reworks your route from where you are.'),
    icon: CloudRain,
    gradient: 'linear-gradient(145deg, #5b6ee1, #2b3591)',
    bubbles: [
      { icon: CloudRain, text: t('Yağmur: kapalı mekanlar öne alındı', 'Rain: indoor places moved up'), style: { top: '16%', left: '6%' } },
    ],
  },
]

export function OnboardingPage() {
  const [index, setIndex] = useState(0)
  const navigate = useNavigate()
  const t = useT()
  const SLIDES = slides(t)
  const slide = SLIDES[index]
  const last = index === SLIDES.length - 1
  const SlideIcon = slide.icon

  function finish(to: string) {
    markOnboarded()
    navigate(to, { replace: true })
  }

  return (
    <div className="app">
      <div className="onboarding">
        <div className="row-between">
          <BrandMark />
          <div className="row" style={{ gap: 8 }}>
            {index === 0 && <LanguageSwitch />}
            {!last && <button className="btn btn-ghost btn-sm" onClick={() => finish('/')}>{t('Atla', 'Skip')}</button>}
          </div>
        </div>

        <div className="onb-art" style={{ background: slide.gradient }} key={index}>
          <div className="big"><SlideIcon size={56} strokeWidth={1.6} /></div>
          {slide.bubbles.map(({ icon: Icon, text, style }) => (
            <span key={text} className="onb-bubble" style={style}><Icon size={16} />{text}</span>
          ))}
        </div>

        <div className="stack" style={{ gap: 10 }}>
          <h1 className="t-display">{slide.title}</h1>
          <p className="ink-2 t-body">{slide.text}</p>
        </div>

        <div className="onb-dots" aria-hidden>
          {SLIDES.map((_, i) => <span key={i} className={i === index ? 'on' : ''} />)}
        </div>

        <div className="stack" style={{ gap: 10 }}>
          <button className="btn btn-primary btn-lg btn-block" onClick={() => (last ? finish('/') : setIndex(index + 1))}>
            {last ? t('Keşfetmeye başla', 'Start exploring') : t('Devam', 'Next')} <ArrowRight size={18} />
          </button>
          {last && (
            <button className="btn btn-ghost btn-block" onClick={() => finish('/login')}>{t('Zaten hesabım var', 'I already have an account')}</button>
          )}
        </div>
      </div>
    </div>
  )
}
