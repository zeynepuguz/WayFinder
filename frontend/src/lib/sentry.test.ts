import { maskEmails } from './sentry'

describe('sentry', () => {
  it('masks e-mail addresses in error texts', () => {
    expect(maskEmails('No account for zeynep.u+test@gmail.com')).toBe('No account for [email]')
    expect(maskEmails('Route 7 not found')).toBe('Route 7 not found')
    expect(maskEmails(undefined)).toBeUndefined()
  })
})
