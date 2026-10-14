import { Bookmark, Compass, House, Lock, MessageCircle, Route, type LucideIcon } from 'lucide-react'
import { NavLink, Outlet } from 'react-router'
import { useAuth } from '../context/AuthContext'
import { useT } from '../lib/i18n'

const TABS: { to: string; icon: LucideIcon; label: [string, string]; end?: boolean; paid?: boolean }[] = [
  { to: '/', icon: House, label: ['Ana Sayfa', 'Home'], end: true },
  { to: '/explore', icon: Compass, label: ['Keşfet', 'Explore'] },
  { to: '/assistant', icon: MessageCircle, label: ['Asistan', 'Assistant'], paid: true },
  { to: '/routes', icon: Route, label: ['Rotalarım', 'My routes'], paid: true },
  { to: '/saved', icon: Bookmark, label: ['Kaydedilenler', 'Saved'], paid: true },
]

export function AppLayout() {
  const { hasAccess } = useAuth()
  const t = useT()

  return (
    <div className="app">
      <Outlet />
      <nav className="tabbar" aria-label={t('Ana menü', 'Main menu')}>
        {TABS.map(({ to, icon: Icon, label, end, paid }) => (
          <NavLink key={to} to={to} end={end} className="tab">
            <span className="tab-icon"><Icon size={22} strokeWidth={1.8} /></span>
            {t(label[0], label[1])}
            {paid && !hasAccess && <Lock size={11} className="tab-lock" aria-label="Premium" />}
          </NavLink>
        ))}
      </nav>
    </div>
  )
}
