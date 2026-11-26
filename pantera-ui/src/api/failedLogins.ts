import { getApiClient } from './client'
import type { FailedLogins } from '@/types'

/**
 * GET/PUT /admin/failed-logins-settings: the login delay and the account
 * lock (2.2.15). All values are strings, like every other settings
 * endpoint; the two `*_seconds` keys bound the delay, `failed_logins_lock_after`
 * is the number of failed logins that locks a user (0 = no lock).
 *
 * NOT the same limit as `LoginThrottleSettings` (`./auth`): that one belongs
 * to the sign-in form and counts per user and client address.
 */
export interface FailedLoginsSettings {
  failed_logins_delay_from_failure: string
  failed_logins_first_delay_seconds: string
  failed_logins_max_delay_seconds: string
  failed_logins_lock_after: string
  failed_logins_unlock_after_minutes: string
  failed_logins_forget_after_minutes: string
  failed_logins_count_rejected_keys: string
  failed_logins_lock_all_credentials: string
}

export async function getFailedLoginsSettings(): Promise<FailedLoginsSettings> {
  const { data } = await getApiClient().get<FailedLoginsSettings>(
    '/admin/failed-logins-settings',
  )
  return data
}

export async function updateFailedLoginsSettings(
  settings: Partial<FailedLoginsSettings>,
): Promise<void> {
  await getApiClient().put('/admin/failed-logins-settings', settings)
}

/** Every locked name, oldest lock first. Needs the permission to read users. */
export async function listLockedUsers(): Promise<FailedLogins[]> {
  const { data } = await getApiClient().get<{ items?: FailedLogins[] }>('/locked-users')
  return data.items ?? []
}

/** Count, lock and last failed login of one name; the name need not be a user. */
export async function getFailedLogins(name: string): Promise<FailedLogins> {
  const { data } = await getApiClient().get<FailedLogins>(
    `/users/${encodeURIComponent(name)}/failed-logins`,
  )
  return data
}

/**
 * Remove the lock and the failed-login count of one name. Needs the
 * permission to enable or disable a user.
 *
 * @returns whether the name was locked; its count is cleared either way
 */
export async function unlockUser(name: string): Promise<boolean> {
  const { data } = await getApiClient().post<{ unlocked?: boolean }>(
    `/users/${encodeURIComponent(name)}/unlock`,
  )
  return data.unlocked === true
}

/** Remove every lock. @returns the names that were locked */
export async function unlockAllUsers(): Promise<string[]> {
  const { data } = await getApiClient().post<{ unlocked?: string[] }>('/users/unlock-all')
  return data.unlocked ?? []
}
