// A `next` query parameter is user-controlled: only same-site paths are followed, so a crafted link
// (?next=//evil.example, ?next=/\evil.example or ?next=https://…) cannot send someone off the site after signing in
export function safeNext(next: string | null | undefined): string | null {
  return next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\') ? next : null
}
