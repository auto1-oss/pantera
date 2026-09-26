import { getApiClient } from './client'
import type { PaginatedResponse, User } from '@/types'

export async function listUsers(params: {
  page?: number; size?: number; q?: string; status?: string
} = {}): Promise<PaginatedResponse<User>> {
  const { data } = await getApiClient().get('/users', { params })
  return data
}

export async function getUser(name: string): Promise<User> {
  const { data } = await getApiClient().get<User>(`/users/${name}`)
  return data
}

export async function putUser(name: string, body: Record<string, unknown>): Promise<void> {
  await getApiClient().put(`/users/${name}`, body)
}

export async function deleteUser(name: string): Promise<void> {
  await getApiClient().delete(`/users/${name}`)
}

/**
 * Change a user's password.
 *
 * Self-service (changing your own password): needs no permission grant,
 * but `oldPass` must be your current stored password; the backend returns
 * 403 if it is missing or does not match (a session token is not accepted
 * as the current password).
 *
 * Reset (changing someone ELSE's password): omit `oldPass` (or pass an
 * empty string). The caller needs the change_password permission and must
 * pass the privilege ceiling: without all_permission, the backend returns
 * 403 when the target is an administrator (holds all_permission) or holds
 * any role the caller does not hold.
 */
export async function changePassword(
  name: string,
  oldPass: string | null,
  newPass: string,
): Promise<void> {
  const body: Record<string, string> = {
    new_pass: newPass,
    new_type: 'plain',
  }
  if (oldPass) {
    body.old_pass = oldPass
  }
  await getApiClient().post(`/users/${name}/password`, body)
}

export async function enableUser(name: string): Promise<void> {
  await getApiClient().post(`/users/${name}/enable`)
}

export async function disableUser(name: string): Promise<void> {
  await getApiClient().post(`/users/${name}/disable`)
}
