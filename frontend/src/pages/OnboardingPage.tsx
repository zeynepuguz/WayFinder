import { ArrowRight, CloudRain, Coffee, MapPin, Route, Sparkles, Wallet, type LucideIcon } from 'lucide-react'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { BrandMark } from '../components/visuals'

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

const SLIDES: {
  title: string
  text: string
  icon: LucideIcon
  gradient: string
  bubbles: { icon: LucideIcon; text: string; style: React.CSSProperties }[]
}[] = [
  {
    title: 'Şehri senin için araştıran asistan',
    text: 'Nerede kahvaltı, nerede kahve, hangi sırayla? Nomi bütçene, zamanına ve zevkine göre gününü planlar.',
    icon: Sparkles,
    gradient: 'linear-gradient(145deg, #ff8a5c, #e8452a)',
    bubbles: [
      { icon: Coffee, text: 'Moda’da nitelikli kahve', style: { top: '18%', left: '8%' } },
      { icon: Wallet, text: '2 kişi · 700 TL', style: { bottom: '20%', right: '8%' } },
    ],
  },
  {
    title: 'Gerçek verilerle, uygulanabilir rotalar',
    text: 'Mekanların açık olduğu saatler, aradaki yürüme mesafesi ve kişi başı harcama hep hesapta.',
    icon: Route,
    gradient: 'linear-gradient(145deg, #1f8a96, #0b4f63)',
    bubbles: [
      { icon: MapPin, text: 'Sonraki durak 350 m', style: { top: '20%', right: '8%' } },
      { icon: Route, text: '09:30 → Kahvaltı', style: { bottom: '22%', left: '8%' } },
    ],
  },
  {
    title: 'Gezerken de yanında',
    text: '“Çok yorulduk”, “yağmur başladı” de; Nomi rotanı bulunduğun yerden yeniden düzenlesin.',
    icon: CloudRain,
    gradient: 'linear-gradient(145deg, #5b6ee1, #2b3591)',
    bubbles: [
      { icon: CloudRain, text: 'Yağmur: kapalı mekanlar öne alındı', style: { top: '16%', left: '6%' } },
    ],
  },
]

export function OnboardingPage() {
  const [index, setIndex] = useState(0)
  const navigate = useNavigate()
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
          {!last && <button className="btn btn-ghost btn-sm" onClick={() => finish('/')}>Atla</button>}
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
            {last ? 'Keşfetmeye başla' : 'Devam'} <ArrowRight size={18} />
          </button>
          {last && (
            <button className="btn btn-ghost btn-block" onClick={() => finish('/login')}>Zaten hesabım var</button>
          )}
        </div>
      </div>
    </div>
  )
}
