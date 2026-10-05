import { mediaUrl } from '../api/client'
import type { Route } from '../api/types'

// Group plans: the invite link the owner sends and the code a friend pastes

// https://<domain>/join/<code>: the website and the API share a domain (the app: the host of VITE_API_BASE_URL)
export function inviteLink(code: string, origin = window.location.origin): string {
  return new URL(mediaUrl(`/join/${code}`) ?? `/join/${code}`, origin).href
}

// A pasted invite link or the bare code; null = neither
export function inviteCode(input: string): string | null {
  const text = input.trim()
  const fromLink = /\/join\/([A-Za-z0-9_-]{8,32})\/?(?:[?#].*)?$/.exec(text)
  if (fromLink) return fromLink[1]
  return /^[A-Za-z0-9_-]{8,32}$/.test(text) ? text : null
}

// Shared with someone: there is an invite code or a member besides the owner
export function isGroupRoute(route: Route): boolean {
  return !!route.group && (route.group.shareToken != null || route.group.members.length > 1)
}
