import { tr } from './i18n'

// The same rules as the server (backend service/Usernames): 3-20 characters, lowercase letters, digits, "_" and
// ".", starting with a letter; no "..", not ending with "."
const VALID = /^[a-z][a-z0-9_.]{2,19}$/
const RESERVED = new Set(['admin', 'administrator', 'nomi', 'nomiapp', 'nomi_app', 'destek', 'support', 'yonetim',
  'yonetici', 'moderator', 'mod', 'root', 'system', 'sistem', 'resmi', 'official'])

/** "Zeynep_7 " -> "zeynep_7", "IŞIL" -> "isil": lowercase, Turkish letters in their plain form. */
export function normalizeUsername(value: string): string {
  return value.trim().toLocaleLowerCase('tr').replace(/ı/g, 'i').normalize('NFD').replace(/[̀-ͯ]/g, '')
}

/** Why the username cannot be used, or null when it can (whether it is free only the server knows). */
export function usernameProblem(value: string): string | null {
  const name = normalizeUsername(value)
  if (name.length < 3) return tr('Kullanıcı adı en az 3 karakter olmalı.', 'The username needs at least 3 characters.')
  if (name.length > 20) return tr('Kullanıcı adı en fazla 20 karakter olabilir.', 'The username can have at most 20 characters.')
  if (!/^[a-z]/.test(name)) return tr('Kullanıcı adı bir harfle başlamalı.', 'The username must start with a letter.')
  if (!VALID.test(name) || name.includes('..') || name.endsWith('.')) {
    return tr('Sadece harf, rakam, alt çizgi (_) ve nokta (.) kullanabilirsin.', 'Use only letters, digits, underscores (_) and dots (.).')
  }
  if (RESERVED.has(name)) return tr('Bu kullanıcı adı kullanılamaz.', 'This username can’t be used.')
  return null
}

export const USERNAME_TAKEN = () => tr('Bu kullanıcı adı alınmış, başka bir tane dene.', 'This username is taken, try another one.')
