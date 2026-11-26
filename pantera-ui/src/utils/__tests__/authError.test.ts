import { describe, it, expect, beforeEach, vi } from 'vitest'
import { AxiosHeaders } from 'axios'
import {
  ACCOUNT_LOCKED_MESSAGE, formatAuthError,
  forgetSignOutReason, rememberSignOutReason, signOutNotice,
} from '../authError'

const GENERIC = 'Sign-in failed. Check your credentials and try again.'
const UNAVAILABLE = 'Sign-in is temporarily unavailable. Please try again in a moment.'

/** A failed API call the way axios reports it. */
function refused(status: number, data?: unknown, headers: unknown = {}) {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    isAxiosError: true,
    response: { status, data, headers },
  })
}

describe('formatAuthError', () => {
  beforeEach(() => {
    // The helper logs status and code for developers; keep the test output clean.
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  it('tells a locked user to ask an administrator', () => {
    const err = refused(403, { error: 'ACCOUNT_LOCKED', message: 'whatever the server says', status: 403 })
    expect(formatAuthError(err)).toEqual({ kind: 'locked', message: ACCOUNT_LOCKED_MESSAGE })
  })

  it('words the locked message as the sign-in endpoint does', () => {
    expect(ACCOUNT_LOCKED_MESSAGE).toBe('Account locked. Ask an administrator to unlock it.')
  })

  it('keeps the generic message for any other 403', () => {
    const err = refused(403, { error: 'FORBIDDEN', message: 'User alice is disabled' })
    expect(formatAuthError(err)).toEqual({ kind: 'credentials', message: GENERIC })
  })

  it('takes the seconds of a login delay from Retry-After', () => {
    const err = refused(
      429,
      { error: 'LOGIN_DELAYED', message: 'Too many failed logins. Try again in 99 seconds.', status: 429 },
      { 'retry-after': '7' },
    )
    expect(formatAuthError(err)).toEqual({
      kind: 'delayed', message: 'Too many failed logins. Try again in 7 seconds.',
    })
  })

  it('reads Retry-After from axios headers, whatever the case of its name', () => {
    const axiosHeaders = refused(429, { error: 'LOGIN_DELAYED' }, new AxiosHeaders({ 'Retry-After': '12' }))
    const plainHeaders = refused(429, { error: 'LOGIN_DELAYED' }, { 'Retry-After': 30 })
    expect(formatAuthError(axiosHeaders).message, 'AxiosHeaders')
      .toBe('Too many failed logins. Try again in 12 seconds.')
    expect(formatAuthError(plainHeaders).message, 'a plain object with a numeric value')
      .toBe('Too many failed logins. Try again in 30 seconds.')
  })

  it('says "1 second" for a delay of one second', () => {
    const err = refused(429, { error: 'LOGIN_DELAYED' }, { 'retry-after': '1' })
    expect(formatAuthError(err).message).toBe('Too many failed logins. Try again in 1 second.')
  })

  it('falls back to the server message when Retry-After is missing', () => {
    const err = refused(429, {
      error: 'LOGIN_DELAYED', message: 'Too many failed logins. Try again in 4 seconds.', status: 429,
    })
    expect(formatAuthError(err)).toEqual({
      kind: 'delayed', message: 'Too many failed logins. Try again in 4 seconds.',
    })
  })

  it.each(['', 'soon', '-3', '0', '1.5', 'Wed, 21 Oct 2026 07:28:00 GMT'])(
    'falls back to the server message when Retry-After is %j',
    (value) => {
      const err = refused(
        429,
        { error: 'LOGIN_DELAYED', message: 'Too many failed logins. Try again in 4 seconds.' },
        { 'retry-after': value },
      )
      expect(formatAuthError(err).message).toBe('Too many failed logins. Try again in 4 seconds.')
    },
  )

  it('names no number when neither the header nor a message says one', () => {
    const noBody = refused(429, { error: 'LOGIN_DELAYED' })
    const noHeaders = refused(429, { error: 'LOGIN_DELAYED', message: '  ' }, undefined)
    expect(formatAuthError(noBody).message, 'without a message')
      .toBe('Too many failed logins. Try again later.')
    expect(formatAuthError(noHeaders).message, 'without headers and with a blank message')
      .toBe('Too many failed logins. Try again later.')
  })

  it('keeps the generic message for the older limit of the form', () => {
    const err = refused(
      429,
      { error: 'TOO_MANY_REQUESTS', message: 'Too many sign-in attempts. Please wait and try again.', status: 429 },
      { 'retry-after': '857' },
    )
    expect(formatAuthError(err)).toEqual({ kind: 'credentials', message: GENERIC })
  })

  it('shows the server message of a 503', () => {
    const err = refused(503, {
      error: 'SERVICE_UNAVAILABLE', message: 'Sign-in is temporarily unavailable. Please try again.',
    })
    expect(formatAuthError(err)).toEqual({
      kind: 'unavailable', message: 'Sign-in is temporarily unavailable. Please try again.',
    })
  })

  it('uses its own message for a 503 without one', () => {
    expect(formatAuthError(refused(503)).message, 'no body').toBe(UNAVAILABLE)
    expect(formatAuthError(refused(503, '<html>503 Service Unavailable</html>')).message, 'a proxy page')
      .toBe(UNAVAILABLE)
    expect(formatAuthError(refused(503, { error: 'SERVICE_UNAVAILABLE', message: 42 })).message, 'no text')
      .toBe(UNAVAILABLE)
  })

  it('never shows the server message of another failure', () => {
    const cases: Array<[number, string]> = [
      [401, GENERIC],
      [403, GENERIC],
      [429, GENERIC],
      [400, 'Sign-in provider is not configured. Contact your administrator.'],
      [500, UNAVAILABLE],
      [502, UNAVAILABLE],
    ]
    for (const [status, expected] of cases) {
      const err = refused(status, { error: 'X', message: 'user alice exists, wrong password' })
      expect(formatAuthError(err).message, `status ${status}`).toBe(expected)
    }
  })

  it('does not take a code from the wrong status', () => {
    const lockedAs401 = refused(401, { error: 'ACCOUNT_LOCKED' })
    const delayedAs403 = refused(403, { error: 'LOGIN_DELAYED' }, { 'retry-after': '5' })
    expect(formatAuthError(lockedAs401).message, 'ACCOUNT_LOCKED on 401').toBe(GENERIC)
    expect(formatAuthError(delayedAs403).message, 'LOGIN_DELAYED on 403').toBe(GENERIC)
  })

  it('reports a network failure as unavailable', () => {
    const err = Object.assign(new Error('Network Error'), { isAxiosError: true })
    expect(formatAuthError(err)).toEqual({ kind: 'unavailable', message: UNAVAILABLE })
  })
})

describe('sign-out notice', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('is empty when no session was ended', () => {
    expect(signOutNotice()).toBe('')
  })

  it('is the locked message after a locked user was signed out, until it is forgotten', () => {
    rememberSignOutReason('account_locked')
    expect(signOutNotice(), 'first read').toBe(ACCOUNT_LOCKED_MESSAGE)
    expect(signOutNotice(), 'reading must not clear it').toBe(ACCOUNT_LOCKED_MESSAGE)
    forgetSignOutReason()
    expect(signOutNotice(), 'after it was forgotten').toBe('')
  })

  it('does without the notice where session storage is refused', () => {
    const refuse = () => { throw new DOMException('denied', 'SecurityError') }
    vi.stubGlobal('sessionStorage', { setItem: refuse, getItem: refuse, removeItem: refuse })
    try {
      expect(() => rememberSignOutReason('account_locked'), 'the sign-out must go on').not.toThrow()
      expect(signOutNotice(), 'the sign-in page shows nothing').toBe('')
      expect(() => forgetSignOutReason(), 'a new sign-in must go on').not.toThrow()
    } finally {
      vi.unstubAllGlobals()
    }
    expect(signOutNotice(), 'nothing was stored meanwhile').toBe('')
  })

  it('shows nothing for a reason it does not know', () => {
    sessionStorage.setItem('sign_out_reason', '<b>you have been hacked</b>')
    expect(signOutNotice(), 'a foreign value').toBe('')
    sessionStorage.setItem('sign_out_reason', 'toString')
    expect(signOutNotice(), 'a name every object has').toBe('')
  })
})
