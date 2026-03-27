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

export async function changePassword(name: string, oldPass: string, newPass: string): Promise<void> {
  await getApiClient().post(`/users/${name}/password`, { old_pass: oldPass, new_pass: newPass, new_type: 'plain' })
}

export async function enableUser(name: string): Promise<void> {
  await getApiClient().post(`/users/${name}/enable`)
}

export async function disableUser(name: string): Promise<void> {
  await getApiClient().post(`/users/${name}/disable`)
}
