/**
 * Texts of the sign-in failures report, shared by the administrators'
 * page and the profile page. A reason is shown as text with its token as
 * the tooltip; a token this UI does not know is shown as it is.
 */

const REJECTED_KEY_REASONS: Record<string, string> = {
  api_key_expired: 'Expired',
  api_key_revoked: 'Revoked',
  api_key_refused: 'Not accepted',
  api_key_unknown: 'Not issued here',
}

const PASSWORD_FAILURE_REASONS: Record<string, string> = {
  wrong_credentials: 'Wrong credentials',
  login_delayed: 'Login delayed',
  account_locked: 'Account locked',
}

const SECOND_FACTOR_REASONS: Record<string, string> = {
  second_factor_failed: 'Second factor not confirmed',
  sign_in_paused: 'Refused during a pause',
}

/** Why an API key was rejected. */
export function rejectedKeyReason(token: string): string {
  return REJECTED_KEY_REASONS[token] ?? (token || '—')
}

/** Why the last password login of a line failed or was refused. */
export function passwordFailureReason(token: string): string {
  return PASSWORD_FAILURE_REASONS[token] ?? (token || '—')
}

/** Why the last sign-in of a second-factor line failed or was refused. */
export function secondFactorReason(token: string): string {
  return SECOND_FACTOR_REASONS[token] ?? (token || '—')
}

/** Number of characters of a key id that are shown. */
const KEY_ID_SHOWN = 8

/** A key id cut to its first characters; the whole id belongs in a tooltip. */
export function shortKeyId(id: string): string {
  return id.length > KEY_ID_SHOWN ? `${id.slice(0, KEY_ID_SHOWN)}…` : id
}

/**
 * What a rejected key is called: the name its owner gave it, else its id
 * shortened. A key this Pantera did not issue has neither.
 */
export function rejectedKeyName(key: { key_name: string | null; key_id: string }): string {
  return key.key_name || shortKeyId(key.key_id) || '—'
}

/** The user of a line; a request need not carry a name. */
export function userOrNoName(user: string): string {
  return user || '(no name)'
}
