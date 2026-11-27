/**
 * Auth error normalizer.
 *
 * Maps any thrown value (axios error, plain Error, unknown) into a fixed
 * set of user-facing messages for sign-in / SSO flows. The whole point is
 * that the UI MUST NOT echo backend strings or axios diagnostics — those
 * can leak whether a user exists, whether their password was wrong, which
 * IdP rejected them, or which provider is down. All of those are useful
 * to attackers and useless to legitimate users.
 *
 * The backend already returns generic 401s for the auth endpoints, but
 * this helper is the second line of defense: even if a future code path
 * starts leaking detail, the UI will still display one of the fixed
 * messages below.
 *
 * Four refusals are told to the person, because without them nobody can
 * know what to do, and none says whether the user exists or the password
 * was right (2.2.15):
 *  - 403 `ACCOUNT_LOCKED`: the account is locked after failed logins;
 *  - 403 `LOGIN_DELAYED`: logins are delayed, for the seconds in `Retry-After`;
 *  - 403 `SIGN_IN_PAUSED`: password sign-in is paused after too many
 *    sign-ins whose second factor was not confirmed, for the seconds in
 *    `Retry-After`. It is answered during the pause without a check;
 *  - 503: sign-in cannot be checked right now, in the server's own words.
 * The three 403s are told apart by their code alone. A 429 is not one of
 * the four and keeps the generic message.
 * Only the delay, the pause and the 503 may show the server's message, and
 * only as described at {@link delayedMessage}, {@link pausedMessage} and
 * {@link serverMessage}.
 *
 * Detail (status code, raw message) goes to console.warn for the
 * developer console only — never into the DOM.
 */

import type { AxiosError } from 'axios'

export type AuthErrorKind =
  | 'credentials' // 401 / 403 — bad creds, MFA fail, account disabled, anything auth-related
  | 'misconfigured' // 400 / 404 — provider not configured, callback URL mismatch
  | 'unavailable' // 5xx, network — backend down, IdP unreachable
  | 'state' // OAuth state mismatch — possible CSRF or stale tab
  | 'locked' // 403 ACCOUNT_LOCKED — locked after failed logins, an administrator unlocks
  | 'delayed' // 403 LOGIN_DELAYED — logins of this user are refused for some seconds
  | 'paused' // 403 SIGN_IN_PAUSED — password sign-in paused after unconfirmed second factors

export interface NormalizedAuthError {
  kind: AuthErrorKind
  /** Persistent message safe to render to the user. */
  message: string
}

/** What a locked user is told, on the sign-in page and after a session was ended. */
export const ACCOUNT_LOCKED_MESSAGE = 'Account locked. Ask an administrator to unlock it.'

const MESSAGES: Record<AuthErrorKind, string> = {
  credentials: 'Sign-in failed. Check your credentials and try again.',
  misconfigured: 'Sign-in provider is not configured. Contact your administrator.',
  unavailable: 'Sign-in is temporarily unavailable. Please try again in a moment.',
  state: 'Your sign-in session expired or is invalid. Please start again.',
  locked: ACCOUNT_LOCKED_MESSAGE,
  delayed: 'Too many failed logins. Try again later.',
  paused: 'Too many sign-ins without a confirmed second factor. Try again later.',
}

/**
 * Why the API client ended a session. It is left in session storage for
 * the sign-in page, which is reached by a full page load.
 */
export type SignOutReason = 'account_locked'

const SIGN_OUT_REASON_KEY = 'sign_out_reason'

const SIGN_OUT_NOTICES: Record<SignOutReason, string> = {
  account_locked: ACCOUNT_LOCKED_MESSAGE,
}

/**
 * Called by the API client when it ends a session for a reason worth
 * telling. The notice is a courtesy: where session storage is refused the
 * sign-out goes on without it.
 */
export function rememberSignOutReason(reason: SignOutReason): void {
  try {
    sessionStorage.setItem(SIGN_OUT_REASON_KEY, reason)
  } catch {
    // No storage, no notice.
  }
}

/**
 * Message for the sign-in page after a forced sign-out, or an empty string.
 * Reading does not clear it: the page can be mounted twice on the way (a
 * route change, then the page load), and the second one must show it too.
 */
export function signOutNotice(): string {
  let reason: string | null = null
  try {
    reason = sessionStorage.getItem(SIGN_OUT_REASON_KEY)
  } catch {
    // No storage, no notice.
  }
  // Own keys only: the value comes from storage and must select a message, never text of its own.
  return reason !== null && Object.hasOwn(SIGN_OUT_NOTICES, reason)
    ? SIGN_OUT_NOTICES[reason as SignOutReason]
    : ''
}

/** Called when the person starts a new sign-in: the notice has been read. */
export function forgetSignOutReason(): void {
  try {
    sessionStorage.removeItem(SIGN_OUT_REASON_KEY)
  } catch {
    // Nothing was stored.
  }
}

function isAxiosError(e: unknown): e is AxiosError {
  return typeof e === 'object' && e !== null && 'isAxiosError' in e
}

/**
 * Convert any thrown value into a NormalizedAuthError. Always succeeds —
 * unknown shapes fall back to the generic credentials message.
 */
export function formatAuthError(err: unknown): NormalizedAuthError {
  // Local marker thrown by the SSO callback view for state mismatches
  if (err instanceof Error && err.message.includes('OAuth state')) {
    logDetail('state', err)
    return { kind: 'state', message: MESSAGES.state }
  }
  if (err instanceof Error && err.message.includes('SSO session data')) {
    logDetail('state', err)
    return { kind: 'state', message: MESSAGES.state }
  }

  if (isAxiosError(err)) {
    const status = err.response?.status
    logDetail('axios', err)
    if (status === undefined || status === 0) {
      return { kind: 'unavailable', message: MESSAGES.unavailable }
    }
    const code = errorCode(err)
    if (status === 403 && code === 'ACCOUNT_LOCKED') {
      return { kind: 'locked', message: MESSAGES.locked }
    }
    // Known by their code alone: the server answers a delay and a pause 403, as it does a lock.
    if (code === 'LOGIN_DELAYED') {
      return { kind: 'delayed', message: delayedMessage(err) }
    }
    if (code === 'SIGN_IN_PAUSED') {
      return { kind: 'paused', message: pausedMessage(err) }
    }
    if (status === 503) {
      return { kind: 'unavailable', message: serverMessage(err) ?? MESSAGES.unavailable }
    }
    if (status >= 500) {
      return { kind: 'unavailable', message: MESSAGES.unavailable }
    }
    if (status === 404 || status === 400) {
      return { kind: 'misconfigured', message: MESSAGES.misconfigured }
    }
    // 401 / 403 / anything else 4xx — treat as a generic credentials failure
    return { kind: 'credentials', message: MESSAGES.credentials }
  }

  logDetail('unknown', err)
  return { kind: 'credentials', message: MESSAGES.credentials }
}

/** The `error` code of the API's `{ error, message, status }` body, if any. */
function errorCode(err: AxiosError): string | undefined {
  const data = err.response?.data
  if (data && typeof data === 'object') {
    const code = (data as { error?: unknown }).error
    if (typeof code === 'string') return code
  }
  return undefined
}

/**
 * The `message` of the API's error body. Shown for three answers only: the
 * login delay when its seconds cannot be read from the header, the pause
 * of password sign-in, and a 503 of the sign-in. The server words them for
 * the person and puts no exception text in them.
 */
function serverMessage(err: AxiosError): string | undefined {
  const data = err.response?.data
  if (data && typeof data === 'object') {
    const message = (data as { message?: unknown }).message
    if (typeof message === 'string' && message.trim().length > 0) return message.trim()
  }
  return undefined
}

/**
 * Seconds of a `Retry-After` header, or null when it is missing, not a
 * whole number of seconds, or hidden from scripts: a browser shows a
 * cross-origin page only the response headers the server exposes.
 */
function retryAfterSeconds(err: AxiosError): number | null {
  const headers = err.response?.headers as unknown
  let raw: unknown
  if (headers && typeof (headers as { get?: unknown }).get === 'function') {
    raw = (headers as { get: (name: string) => unknown }).get('retry-after')
  } else if (headers && typeof headers === 'object') {
    const found = Object.entries(headers as Record<string, unknown>)
      .find(([name]) => name.toLowerCase() === 'retry-after')
    raw = found?.[1]
  }
  const text = typeof raw === 'number' ? String(raw) : typeof raw === 'string' ? raw.trim() : ''
  if (!/^\d+$/.test(text)) return null
  const seconds = parseInt(text, 10)
  return seconds > 0 ? seconds : null
}

/**
 * "Try again in N seconds" from `Retry-After`; without a readable header
 * the server's message, which carries the same number; else no number.
 */
function delayedMessage(err: AxiosError): string {
  const seconds = retryAfterSeconds(err)
  if (seconds !== null) {
    return `Too many failed logins. Try again in ${seconds} second${seconds === 1 ? '' : 's'}.`
  }
  return serverMessage(err) ?? MESSAGES.delayed
}

/**
 * The server's message of a pause, which names the minutes as the server
 * rounds them; without one "Try again in N minutes" from `Retry-After`,
 * rounded up; else no number.
 */
function pausedMessage(err: AxiosError): string {
  const message = serverMessage(err)
  if (message !== undefined) return message
  const seconds = retryAfterSeconds(err)
  if (seconds !== null) {
    const minutes = Math.ceil(seconds / 60)
    return 'Too many sign-ins without a confirmed second factor. '
      + `Try again in ${minutes} minute${minutes === 1 ? '' : 's'}.`
  }
  return MESSAGES.paused
}

function logDetail(tag: string, err: unknown): void {
  // Developer console only — never reaches the DOM. Useful for debugging
  // a real user-reported issue without leaking anything to a casual viewer.
  //
  // B4: dumping the raw err (typically an AxiosError) pulls the full
  // request config — including Authorization header, request body, and
  // any user-typed password — into console output, browser extensions,
  // Sentry breadcrumbs, etc. Emit only the shape we actually need.
  const detail: { status?: number; code?: string } = {}
  if (err && typeof err === 'object') {
    const candidate = err as {
      response?: { status?: number }
      code?: string
    }
    if (candidate.response && typeof candidate.response.status === 'number') {
      detail.status = candidate.response.status
    }
    if (typeof candidate.code === 'string') {
      detail.code = candidate.code
    }
  }
   
  console.warn(`[auth] ${tag} error`, detail)
}
