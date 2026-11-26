import { getApiClient } from './client'
import type { PaginatedResponse } from '@/types'

/**
 * The sign-in failures report (2.2.15): who fails to sign in, with what,
 * and from where. One line per user, client address and API key, the most
 * recent first. The node that is asked answers with what it noted up to
 * now; what another node noted appears within a minute.
 *
 * `user`, `client_ip` and `user_agent` can be empty strings.
 */

/** Where a failed sign-in came from. Part of every line. */
interface SignInOrigin {
  user: string
  client_ip: string
  user_agent: string
  /** `repository` or `api`; `ui` for the sign-in form. */
  path: string
}

/**
 * A rejected API key. `reason` is one of
 *
 *   - `api_key_expired` — past its expiry date;
 *   - `api_key_revoked` — revoked or removed;
 *   - `api_key_refused` — valid but not accepted: its owner is disabled,
 *     or it was sent with another user name;
 *   - `api_key_unknown` — this Pantera did not sign it. Then `verified` is
 *     false, `key_id` is empty, and `user` is only the name the client
 *     sent, which proves nothing.
 *
 * `key_name` and the two key dates are null for a key that is not stored
 * (any more); `key_expires_at` alone is null for a key without an end.
 */
export interface RejectedApiKey extends SignInOrigin {
  verified: boolean
  key_id: string
  key_name: string | null
  key_created_at: string | null
  key_expires_at: string | null
  reason: string
  rejected: number
  first_rejected_at: string | null
  last_rejected_at: string | null
}

/**
 * What applies to a user now, the same on every line of that user: the
 * failed logins on record, the lock, the seconds a login is still delayed
 * (0 for none), and the last unlock by an administrator.
 */
export interface PasswordLoginState {
  failures: number
  locked: boolean
  locked_at: string | null
  delayed_seconds: number
  unlocked_at: string | null
  unlocked_by: string | null
}

/**
 * Failed password logins of one user from one client address. `failed`
 * counts the logins that were checked and failed (these count towards the
 * lock), `refused` those refused without a check, during a delay or a
 * lock. `last_reason` is `wrong_credentials`, `login_delayed` or
 * `account_locked`.
 */
export interface FailedPasswordLogin extends SignInOrigin {
  failed: number
  refused: number
  last_reason: string
  first_failure_at: string | null
  last_failure_at: string | null
  state: PasswordLoginState
}

/**
 * Logins refused by the sign-in form's own limit (System Settings → Login
 * Throttling). `hits` counts the refused logins, `failures` the failures
 * in the window when the limit last refused; `blocked` says whether the
 * block is still on.
 */
export interface FormLimitHit extends SignInOrigin {
  hits: number
  failures: number
  blocked_until: string | null
  blocked: boolean
  first_hit_at: string | null
  last_hit_at: string | null
}

/**
 * Page and filters of a list. A filter matches a part of the value, in
 * any case, on the server. The server's page size is 50, at most 200.
 */
export interface SignInFailuresQuery {
  page?: number
  size?: number
  user?: string
  client_ip?: string
}

/** The query as sent: without a filter that is empty. */
function listParams(query: SignInFailuresQuery): SignInFailuresQuery {
  const params: SignInFailuresQuery = {}
  if (query.page !== undefined) params.page = query.page
  if (query.size !== undefined) params.size = query.size
  const user = query.user?.trim()
  if (user) params.user = user
  const clientIp = query.client_ip?.trim()
  if (clientIp) params.client_ip = clientIp
  return params
}

async function list<T>(
  url: string, query: SignInFailuresQuery, signal?: AbortSignal,
): Promise<PaginatedResponse<T>> {
  const { data } = await getApiClient().get<PaginatedResponse<T>>(
    url, { params: listParams(query), signal },
  )
  return data
}

/** Rejected API keys of every user. Needs the permission to read users. */
export function listRejectedApiKeys(
  query: SignInFailuresQuery = {}, signal?: AbortSignal,
): Promise<PaginatedResponse<RejectedApiKey>> {
  return list<RejectedApiKey>('/sign-in-failures/api-keys', query, signal)
}

/** Failed password logins of every user. Needs the permission to read users. */
export function listFailedPasswordLogins(
  query: SignInFailuresQuery = {}, signal?: AbortSignal,
): Promise<PaginatedResponse<FailedPasswordLogin>> {
  return list<FailedPasswordLogin>('/sign-in-failures/passwords', query, signal)
}

/** Logins the sign-in form's own limit refused. Needs the permission to read users. */
export function listFormLimitHits(
  query: SignInFailuresQuery = {}, signal?: AbortSignal,
): Promise<PaginatedResponse<FormLimitHit>> {
  return list<FormLimitHit>('/sign-in-failures/form-limit', query, signal)
}

/** The rejected API keys of the signed-in user, for every signed-in user. */
export async function listMyRejectedKeys(
  query: { page?: number; size?: number } = {},
): Promise<RejectedApiKey[]> {
  const { data } = await getApiClient().get<{ items?: RejectedApiKey[] }>(
    '/auth/rejected-keys', { params: query },
  )
  return data.items ?? []
}

/**
 * GET/PUT /admin/sign-in-report-settings. Both values are strings, like
 * every other settings endpoint: `true`/`false`, and 1 to 3650 days. With
 * the report off nothing is noted or stored; lines already stored stay
 * until their retention ends.
 */
export interface SignInReportSettings {
  sign_in_report_enabled: string
  sign_in_report_retention_days: string
}

export async function getSignInReportSettings(): Promise<SignInReportSettings> {
  const { data } = await getApiClient().get<SignInReportSettings>(
    '/admin/sign-in-report-settings',
  )
  return data
}

export async function updateSignInReportSettings(
  settings: Partial<SignInReportSettings>,
): Promise<void> {
  await getApiClient().put('/admin/sign-in-report-settings', settings)
}
