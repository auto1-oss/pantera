import { getApiClient } from './client'
import type {
  PaginatedResponse, CursorResponse, RepoMember, RepoListItem,
  TreeEntry, ArtifactDetail, PullInstructions, StorageAlias,
} from '@/types'

export async function listRepos(params: {
  page?: number; size?: number; type?: string; q?: string
} = {}, signal?: AbortSignal): Promise<PaginatedResponse<RepoListItem>> {
  const { data } = await getApiClient().get('/repositories', { params, signal })
  return data
}

export async function getRepo(name: string): Promise<Record<string, unknown>> {
  const { data } = await getApiClient().get<Record<string, unknown>>(`/repositories/${name}`)
  return data
}

export async function repoExists(name: string): Promise<boolean> {
  try {
    await getApiClient().head(`/repositories/${name}`)
    return true
  } catch {
    return false
  }
}

export async function putRepo(name: string, config: Record<string, unknown>): Promise<void> {
  await getApiClient().put(`/repositories/${name}`, config)
}

export async function deleteRepo(name: string): Promise<void> {
  await getApiClient().delete(`/repositories/${name}`)
}

export async function moveRepo(name: string, newName: string): Promise<void> {
  await getApiClient().put(`/repositories/${name}/move`, { new_name: newName })
}

export async function getMembers(name: string): Promise<RepoMember[]> {
  const { data } = await getApiClient().get<{ members: RepoMember[] }>(`/repositories/${name}/members`)
  return data.members ?? []
}

export async function getTree(name: string, params: {
  path?: string
  limit?: number
  marker?: string
  sort?: 'name' | 'date'
  sort_dir?: 'asc' | 'desc'
} = {}, signal?: AbortSignal): Promise<CursorResponse<TreeEntry>> {
  const { data } = await getApiClient().get(`/repositories/${name}/tree`, { params, signal })
  return data
}

export async function getArtifactDetail(name: string, path: string): Promise<ArtifactDetail> {
  const { data } = await getApiClient().get<ArtifactDetail>(`/repositories/${name}/artifact`, {
    params: { path },
  })
  return data
}

export async function getPullInstructions(name: string, path: string): Promise<PullInstructions> {
  const { data } = await getApiClient().get<PullInstructions>(`/repositories/${name}/artifact/pull`, {
    params: { path },
  })
  return data
}

export async function deleteArtifacts(name: string, path: string): Promise<void> {
  await getApiClient().delete(`/repositories/${name}/artifacts`, { data: { path } })
}

export async function deletePackages(name: string, packageName: string): Promise<void> {
  await getApiClient().delete(`/repositories/${name}/packages`, {
    params: { package: packageName },
  })
}

// Storage aliases per repo
export async function getRepoStorages(name: string): Promise<StorageAlias[]> {
  const { data } = await getApiClient().get(`/repositories/${name}/storages`)
  return data.items ?? data
}

export async function putRepoStorage(repoName: string, alias: string, config: Record<string, unknown>): Promise<void> {
  await getApiClient().put(`/repositories/${repoName}/storages/${alias}`, config)
}

export async function deleteRepoStorage(repoName: string, alias: string): Promise<void> {
  await getApiClient().delete(`/repositories/${repoName}/storages/${alias}`)
}

// Cooldown per repo
export async function unblockArtifact(name: string, body: Record<string, unknown>): Promise<void> {
  await getApiClient().post(`/repositories/${name}/cooldown/unblock`, body)
}

export async function unblockAll(name: string): Promise<void> {
  await getApiClient().post(`/repositories/${name}/cooldown/unblock-all`)
}
