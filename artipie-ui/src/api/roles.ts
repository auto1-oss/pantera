import { getApiClient } from './client'
import type { PaginatedResponse, Role } from '@/types'

export async function listRoles(params: {
  page?: number; size?: number
} = {}): Promise<PaginatedResponse<Role>> {
  const { data } = await getApiClient().get('/roles', { params })
  return data
}

export async function getRole(name: string): Promise<Role> {
  const { data } = await getApiClient().get<Role>(`/roles/${name}`)
  return data
}

export async function putRole(name: string, body: Record<string, unknown>): Promise<void> {
  await getApiClient().put(`/roles/${name}`, body)
}

export async function deleteRole(name: string): Promise<void> {
  await getApiClient().delete(`/roles/${name}`)
}

export async function enableRole(name: string): Promise<void> {
  await getApiClient().post(`/roles/${name}/enable`)
}

export async function disableRole(name: string): Promise<void> {
  await getApiClient().post(`/roles/${name}/disable`)
}
