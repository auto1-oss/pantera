import { getApiClient } from './client'

/**
 * Cooldown package inspector (admin-only). The server fetches each repo's
 * metadata in-process, exactly as a client would, so these calls can take
 * longer than the default client timeout.
 */
const INSPECT_TIMEOUT_MS = 60_000

export type CooldownState = 'blocked' | 'released' | 'expired' | 'none'

export interface InspectEnvelope {
  l1: { present: boolean; ageMs?: number | null }
  l2: { present: boolean; ttlRemainingMs?: number | null }
}

export interface InspectNegCacheEntry {
  key: string | Record<string, string>
  l1: boolean
  l2: boolean
}

export interface InspectRepo {
  name: string
  mode: 'proxy' | 'group' | 'local' | string
  members?: string[]
  metadata: {
    status?: number
    fetchedVia?: string
    visibleVersions?: string[]
    error?: string
    unsupported?: boolean
  }
  envelope?: InspectEnvelope
  negativeCache?: InspectNegCacheEntry[]
}

export interface InspectVersion {
  version: string
  cooldown: {
    state: CooldownState
    blockedUntil?: string | number | null
    reason?: string | null
    repo?: string | null
  }
  visibleIn: string[]
  hiddenIn: string[]
  mismatch: boolean
}

export interface CooldownInspectResponse {
  package: string
  repoType: string
  node: string
  repos: InspectRepo[]
  versions: InspectVersion[]
}

export interface CooldownInspectParams {
  repoType: string
  package: string
  repo?: string
}

export interface CooldownRefreshResponse {
  before: CooldownInspectResponse
  after: CooldownInspectResponse
}

export async function inspectCooldownPackage(
  params: CooldownInspectParams,
): Promise<CooldownInspectResponse> {
  const query: Record<string, string> = { repoType: params.repoType, package: params.package }
  if (params.repo) query.repo = params.repo
  const { data } = await getApiClient().get('/cooldown/inspect', {
    params: query,
    timeout: INSPECT_TIMEOUT_MS,
  })
  return data
}

export async function refreshCooldownPackage(
  params: CooldownInspectParams,
): Promise<CooldownRefreshResponse> {
  const body: Record<string, string> = { repoType: params.repoType, package: params.package }
  if (params.repo) body.repo = params.repo
  const { data } = await getApiClient().post('/cooldown/refresh-package', body, {
    timeout: INSPECT_TIMEOUT_MS,
  })
  return data
}

/** Repo types the inspector offers (cooldown applies to proxy formats). */
export const INSPECT_REPO_TYPES = [
  { label: 'npm', value: 'npm' },
  { label: 'PyPI', value: 'pypi' },
  { label: 'Maven', value: 'maven' },
  { label: 'Gradle', value: 'gradle' },
  { label: 'PHP (Composer)', value: 'php' },
  { label: 'Go', value: 'go' },
  { label: 'Docker', value: 'docker' },
  { label: 'Helm', value: 'helm' },
  { label: 'NuGet', value: 'nuget' },
  { label: 'RubyGems', value: 'gem' },
  { label: 'File', value: 'file' },
] as const
