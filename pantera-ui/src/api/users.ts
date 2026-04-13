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
 * Self-service: supply the user's current password in `oldPass`.
 * The backend verifies it and returns 403 if it does not match.
 *
 * Admin-reset: omit `oldPass` (or pass an empty string). The backend
 * recognises the caller is changing someone ELSE's password — the
 * route-level change_password permission is authorization enough, so
 * no old password is required.
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
