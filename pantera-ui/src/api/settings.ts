import { getApiClient } from './client'
import type {
  Settings, HealthResponse, DashboardStats, ReposByType,
  StorageAlias, PaginatedResponse, CooldownRepo, BlockedArtifact,
  CooldownConfig,
} from '@/types'

// Settings
export async function getSettings(): Promise<Settings> {
  const { data } = await getApiClient().get<Settings>('/settings')
  return data
}

export async function updatePrefixes(prefixes: string[]): Promise<void> {
  await getApiClient().put('/settings/prefixes', { prefixes })
}

// Health
export async function getHealth(): Promise<HealthResponse> {
  const { data } = await getApiClient().get<HealthResponse>('/health')
  return data
}

// Dashboard
export async function getDashboardStats(): Promise<DashboardStats> {
  const { data } = await getApiClient().get<DashboardStats>('/dashboard/stats')
  return data
}

export async function getDashboardRequests(period = '24h'): Promise<Record<string, unknown>> {
  const { data } = await getApiClient().get('/dashboard/requests', { params: { period } })
  return data
}

export async function getReposByType(): Promise<ReposByType> {
  const { data } = await getApiClient().get<ReposByType>('/dashboard/repos-by-type')
  return data
}

// Global storage aliases
export async function listStorages(): Promise<StorageAlias[]> {
  const { data } = await getApiClient().get('/storages')
  return data.items ?? data
}

export async function putStorage(name: string, config: Record<string, unknown>): Promise<void> {
  await getApiClient().put(`/storages/${name}`, config)
}

export async function deleteStorage(name: string): Promise<void> {
  await getApiClient().delete(`/storages/${name}`)
}

export async function updateSettingsSection(
  section: string,
  data: Record<string, unknown>,
): Promise<void> {
  await getApiClient().put(`/settings/${section}`, data)
}

// Cooldown
export async function getCooldownOverview(): Promise<CooldownRepo[]> {
  const { data } = await getApiClient().get('/cooldown/overview')
  return data.repos ?? []
}

export async function getCooldownBlocked(params: {
  repo?: string; page?: number; size?: number; search?: string;
  sort_by?: string; sort_dir?: string
} = {}, signal?: AbortSignal): Promise<PaginatedResponse<BlockedArtifact>> {
  const { data } = await getApiClient().get('/cooldown/blocked', { params, signal })
  return data
}

export async function getCooldownConfig(): Promise<CooldownConfig> {
  const { data } = await getApiClient().get('/cooldown/config')
  return data
}

export async function updateCooldownConfig(config: CooldownConfig): Promise<void> {
  await getApiClient().put('/cooldown/config', config)
}

// Auth provider management
export async function toggleAuthProvider(id: number, enabled: boolean): Promise<void> {
  await getApiClient().put(`/auth-providers/${id}/toggle`, { enabled })
}

export async function updateAuthProviderConfig(id: number, config: Record<string, unknown>): Promise<void> {
  await getApiClient().put(`/auth-providers/${id}/config`, config)
}
