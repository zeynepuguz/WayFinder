import { Bell, BellOff, Clock } from 'lucide-react'
import { useEffect, useState } from 'react'
import type { Route } from '../api/types'
import { useT } from '../lib/i18n'
import { disableReminders, enableReminders, minutesBehind, remindersAvailable, remindersEnabled, syncReminders } from '../lib/trip'
import { Alert } from './ui'

// Today's route while the user is out: "you are behind, shift the times?" and stop reminders on the phone
export function TripBar({ route, busy, onShift }: { route: Route; busy: boolean; onShift: () => void }) {
  const t = useT()
  const [now, setNow] = useState(() => new Date())
  const [reminders, setReminders] = useState(remindersEnabled)
  const [denied, setDenied] = useState(false)

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000)
    return () => window.clearInterval(timer)
  }, [])

  // Every change of the route (a replan, a visited stop) moves its reminders too
  useEffect(() => {
    void syncReminders(route, t)
  }, [route, reminders, t])

  async function toggle() {
    if (reminders) {
      disableReminders()
      setReminders(false)
      return
    }
    const granted = await enableReminders().catch(() => false)
    setDenied(!granted)
    setReminders(granted)
  }

  const behind = minutesBehind(route, now)

  return (
    <>
      {behind > 0 && (
        <Alert tone="warning" icon={Clock} action={
          <button className="btn btn-sm btn-primary" disabled={busy} onClick={onShift}>{t('Saatleri kaydır', 'Shift times')}</button>
        }>
          <span>{t(`Programın ~${behind} dk gerisindesin. Kalan durakları şimdiden itibaren kaydırabilirim; o saatte kapalı olan yerin yerine yenisi bulunur.`,
            `You are ~${behind} min behind. I can move the remaining stops to later times; a place closed at its new time is swapped.`)}</span>
        </Alert>
      )}
      {remindersAvailable() && route.status !== 'COMPLETED' && (
        <button className="btn btn-ghost btn-block" onClick={() => void toggle()} aria-pressed={reminders}>
          {reminders ? <BellOff size={18} /> : <Bell size={18} />}
          {reminders ? t('Durak hatırlatıcılarını kapat', 'Turn off stop reminders') : t('Durak hatırlatıcılarını aç', 'Turn on stop reminders')}
        </button>
      )}
      {denied && <p className="t-caption">{t('Bildirim izni verilmedi; telefon ayarlarından açabilirsin.', 'Notifications are not allowed; you can turn them on in the phone settings.')}</p>}
    </>
  )
}
