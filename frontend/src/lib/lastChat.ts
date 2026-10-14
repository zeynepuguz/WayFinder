// The assistant chat the user had open: the Asistan tab (/assistant without ?c=) continues it instead of starting an
// empty one. Kept for the session (sessionStorage, else memory); "Yeni sohbet" and signing out forget it.
const KEY = 'nomi.lastChat'
let memory: number | null = null

export function lastChat(): number | null {
  try {
    const stored = Number(sessionStorage.getItem(KEY))
    return Number.isInteger(stored) && stored > 0 ? stored : memory
  } catch {
    return memory
  }
}

export function rememberChat(id: number) {
  memory = id
  try {
    sessionStorage.setItem(KEY, String(id))
  } catch {
    // memory only
  }
}

export function forgetLastChat() {
  memory = null
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // nothing stored
  }
}
