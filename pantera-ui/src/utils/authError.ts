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
 * Detail (status code, raw message) goes to console.warn for the
 * developer console only — never into the DOM.
 */

import type { AxiosError } from 'axios'

export type AuthErrorKind =
  | 'credentials' // 401 / 403 — bad creds, MFA fail, account disabled, anything auth-related
  | 'misconfigured' // 400 / 404 — provider not configured, callback URL mismatch
  | 'unavailable' // 5xx, network — backend down, IdP unreachable
  | 'state' // OAuth state mismatch — possible CSRF or stale tab

export interface NormalizedAuthError {
  kind: AuthErrorKind
  /** Persistent message safe to render to the user. */
  message: string
}

const MESSAGES: Record<AuthErrorKind, string> = {
  credentials: 'Sign-in failed. Check your credentials and try again.',
  misconfigured: 'Sign-in provider is not configured. Contact your administrator.',
  unavailable: 'Sign-in is temporarily unavailable. Please try again in a moment.',
  state: 'Your sign-in session expired or is invalid. Please start again.',
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

function logDetail(tag: string, err: unknown): void {
  // Developer console only — never reaches the DOM. Useful for debugging
  // a real user-reported issue without leaking anything to a casual viewer.
  // eslint-disable-next-line no-console
  console.warn(`[auth] ${tag} error`, err)
}
