import { describe, expect, it } from 'vitest'
import { inviteCode, inviteLink } from './group'

describe('invite codes', () => {
  it('links to the join page on the site', () => {
    expect(inviteLink('AbC_123-xyz0', 'https://nomi.example')).toBe('https://nomi.example/join/AbC_123-xyz0')
  })

  it('reads a pasted link or a bare code', () => {
    expect(inviteCode(' https://nomi.example/join/AbC_123-xyz0 ')).toBe('AbC_123-xyz0')
    expect(inviteCode('https://nomi.example/join/AbC_123-xyz0?x=1')).toBe('AbC_123-xyz0')
    expect(inviteCode('AbC_123-xyz0')).toBe('AbC_123-xyz0')
  })

  it('rejects anything else', () => {
    expect(inviteCode('')).toBeNull()
    expect(inviteCode('kod')).toBeNull()
    expect(inviteCode('https://evil.example/join/../admin')).toBeNull()
    expect(inviteCode('abc def ghi jkl')).toBeNull()
  })
})
