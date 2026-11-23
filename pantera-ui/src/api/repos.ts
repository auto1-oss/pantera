import { getApiClient } from './client'
import type {
  PaginatedResponse, CursorResponse, RepoMember, RepoListItem, RepoMode,
  TreeEntry, ArtifactDetail, PullInstructions, StorageAlias,
  RepoMoveResult, RepoDeleteResult, RepoJob, DeletedRepo, RepoRestoreResult,
} from '@/types'

export type RepoSortField = 'name' | 'type' | 'updated_at'

export async function listRepos(params: {
  page?: number; size?: number; type?: string; q?: string
  mode?: RepoMode; sort?: RepoSortField; order?: 'asc' | 'desc'
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

export interface DeleteRepoOptions {
  /** Also remove the repository from the groups it is a member of. */
  force?: boolean
  /** Restore window in hours; 0 purges at once. Omitted: the server default. */
  retentionHours?: number
}

/**
 * Delete a repository. The repository disappears at once; the server answers
 * 202 with a purge job that removes the data after the restore window. A
 * server without a database answers 200 (done) or a 202 without a job.
 * A 409 means the repository is a member of groups and `force` was not set.
 */
export async function deleteRepo(name: string, opts: DeleteRepoOptions = {}): Promise<RepoDeleteResult> {
  const params: Record<string, string | number> = {}
  if (opts.force) params.force = 'true'
  if (opts.retentionHours !== undefined) params.retention_hours = opts.retentionHours
  const url = `/repositories/${name}`
  const res = Object.keys(params).length > 0
    ? await getApiClient().delete(url, { params })
    : await getApiClient().delete(url)
  if (res.status !== 202) return { kind: 'deleted' }
  const body = (res.data && typeof res.data === 'object' ? res.data : {}) as Record<string, unknown>
  const message = typeof body.message === 'string' ? body.message : ''
  if (typeof body.job_id !== 'number') return { kind: 'deleting', message }
  return {
    kind: 'job',
    jobId: body.job_id,
    repoKey: typeof body.repo_key === 'string' ? body.repo_key : '',
    purgeAfter: typeof body.purge_after === 'string' ? body.purge_after : null,
    removedFromGroups: Array.isArray(body.removed_from_groups) ? body.removed_from_groups as string[] : [],
    message,
  }
}

/**
 * Rename a repository. No data is copied. With `keepOldNameDays` > 0 the old
 * name keeps serving the repository for that many days. Resolves to null
 * when the server answers without a body.
 */
export async function moveRepo(
  name: string, newName: string, keepOldNameDays?: number,
): Promise<RepoMoveResult | null> {
  const body: { new_name: string; keep_old_name_days?: number } = { new_name: newName }
  if (keepOldNameDays !== undefined && keepOldNameDays > 0) body.keep_old_name_days = keepOldNameDays
  const { data } = await getApiClient().put(`/repositories/${name}/move`, body)
  return data && typeof data === 'object' ? data as RepoMoveResult : null
}

// Background jobs of rename and delete
export async function listRepoJobs(signal?: AbortSignal): Promise<RepoJob[]> {
  const { data } = await getApiClient().get<{ items?: RepoJob[] }>('/repository-jobs', { signal })
  return data.items ?? []
}

export async function getRepoJob(id: number): Promise<RepoJob> {
  const { data } = await getApiClient().get<RepoJob>(`/repository-jobs/${id}`)
  return data
}

/** Retry a `failed` job; 409 when it is not failed. */
export async function retryRepoJob(id: number): Promise<RepoJob> {
  const { data } = await getApiClient().post<RepoJob>(`/repository-jobs/${id}/retry`)
  return data
}

/** End the restore window of a delete that is still waiting and purge now; 409 otherwise. */
export async function startRepoJob(id: number): Promise<RepoJob> {
  const { data } = await getApiClient().post<RepoJob>(`/repository-jobs/${id}/start`)
  return data
}

// Deleted repositories whose data is not purged yet
export async function listDeletedRepos(signal?: AbortSignal): Promise<DeletedRepo[]> {
  const { data } = await getApiClient().get<{ items?: DeletedRepo[] }>('/deleted-repositories', { signal })
  return data.items ?? []
}

/** Restore a deleted repository, optionally under another name; 409 once the purge started or the name is taken. */
export async function restoreDeletedRepo(repoKey: string, name?: string): Promise<RepoRestoreResult> {
  const url = `/deleted-repositories/${encodeURIComponent(repoKey)}/restore`
  const { data } = name
    ? await getApiClient().post<RepoRestoreResult>(url, { name })
    : await getApiClient().post<RepoRestoreResult>(url)
  return data
}

export async function getMembers(name: string): Promise<RepoMember[]> {
  const { data } = await getApiClient().get<{ members: RepoMember[] }>(`/repositories/${name}/members`)
  return data.members ?? []
}

export async function getTree(name: string, params: {
  path?: string
  limit?: number
  marker?: string
  sort?: 'name' | 'date' | 'size'
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

// ---------------------------------------------------------------------------
// Bulk anonymous-access policy
// ---------------------------------------------------------------------------

export interface BulkAccessPolicyRequest {
  selector: {
    type: 'hosted' | 'proxy' | 'group' | 'all'
    names?: string[]
  }
  anonymous_read?: boolean
  anonymous_write?: boolean
}

export interface BulkAccessPolicyResult {
  updated: Array<{
    name: string
    previous: { anonymous_read: boolean; anonymous_write: boolean }
    current:  { anonymous_read: boolean; anonymous_write: boolean }
  }>
  skipped: Array<{ name: string; reason: string }>
}

/**
 * POST /api/v1/repositories/access-policy/bulk
 * Applies an anonymous-access patch to a selector-scoped set of repos.
 * At least one of anonymous_read / anonymous_write must be present;
 * omitting a flag leaves it unchanged on each matched repo.
 */
export async function bulkUpdateAccessPolicy(
  body: BulkAccessPolicyRequest,
): Promise<BulkAccessPolicyResult> {
  const { data } = await getApiClient().post<BulkAccessPolicyResult>(
    '/repositories/access-policy/bulk',
    body,
  )
  return data
}
