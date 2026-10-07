import { describe, it, expect } from 'vitest'
import { relativeTime } from '../relativeTime'

describe('relativeTime', () => {
  const now = new Date('2026-10-06T12:00:00Z')

  it('formats', () => {
    expect(relativeTime(null, now)).toBe('')
    expect(relativeTime('2026-10-06T11:59:30Z', now)).toBe('just now')
    expect(relativeTime('2026-10-06T11:15:00Z', now)).toBe('45 minutes ago')
    expect(relativeTime('2026-10-06T07:00:00Z', now)).toBe('5 hours ago')
    expect(relativeTime('2026-10-03T12:00:00Z', now)).toBe('3 days ago')
    expect(relativeTime('2026-08-01T12:00:00Z', now)).toBe('2 months ago')
  })
})
