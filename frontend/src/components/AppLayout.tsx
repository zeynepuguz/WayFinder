import { Compass, Heart, House, Lock, Map, Sparkles, type LucideIcon } from 'lucide-react'
import { NavLink, Outlet } from 'react-router'
import { useAuth } from '../context/AuthContext'

const TABS: { to: string; icon: LucideIcon; label: string; end?: boolean; paid?: boolean }[] = [
  { to: '/', icon: House, label: 'Ana Sayfa', end: true },
  { to: '/explore', icon: Compass, label: 'Keşfet' },
  { to: '/assistant', icon: Sparkles, label: 'Asistan', paid: true },
  { to: '/routes', icon: Map, label: 'Rotalarım', paid: true },
  { to: '/saved', icon: Heart, label: 'Kaydedilenler', paid: true },
]

export function AppLayout() {
  const { hasAccess } = useAuth()

  return (
    <div className="app">
      <Outlet />
      <nav className="tabbar" aria-label="Ana menü">
        {TABS.map(({ to, icon: Icon, label, end, paid }) => (
          <NavLink key={to} to={to} end={end} className="tab">
            <span className="tab-icon"><Icon size={22} strokeWidth={2} /></span>
            {label}
            {paid && !hasAccess && <Lock size={11} className="tab-lock" aria-label="Premium" />}
          </NavLink>
        ))}
      </nav>
    </div>
  )
}
