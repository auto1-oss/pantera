import { getApiClient } from './client'

/**
 * Negative cache admin API (admin-only). The list is cluster-wide: the
 * server reads Valkey (L2) as the source of truth and merges this node's
 * L1; without Valkey it falls back to L1 only and says so via `source`.
 */

export interface NegCacheKey {
  scope: string
  repoType: string
  artifactName: string
  artifactVersion: string
}

export type NegCacheTier = 'L1' | 'L2'

export interface NegCacheEntry {
  key: NegCacheKey
  tiers: NegCacheTier[]
  /** Remaining TTL from Valkey PTTL; null when the entry is L1-only. */
  ttlRemainingMs: number | null
}

export type NegCacheSource = 'L2+L1' | 'L1-only'

export interface NegCacheListResponse {
  items: NegCacheEntry[]
  total: number
  page?: number
  pageSize?: number
  source: NegCacheSource
  node: string
}

export interface NegCacheListParams {
  q?: string
  scope?: string
  repoType?: string
  page?: number
  pageSize?: number
}

export interface NegCacheProbeKey {
  key: NegCacheKey
  l1: boolean
  l2: boolean
  ttlRemainingMs: number | null
}

export interface NegCacheProbeResponse {
  keys: NegCacheProbeKey[]
  shadowed: boolean
  node?: string
}

/** Honest per-tier removal counts reported by the server. */
export interface NegCacheInvalidationCounts {
  l1: number
  l2: number
  node?: string
}

export interface NegCacheStats {
  enabled: boolean
  l1Size: number
  l2Size: number | null
  hitCount: number
  missCount: number
  hitRate: number
  evictionCount: number
  requestCount: number
  node: string
}

export interface NegCachePatternBody {
  scope?: string
  repoType?: string
  artifactName?: string
  version?: string
}

/**
 * The invalidation endpoints historically wrapped the counts as
 * `{invalidated: {l1, l2}}`; the v2 contract returns `{l1, l2}` at the
 * top level. Accept both so the UI keeps working across the rollout.
 */
function toCounts(data: unknown): NegCacheInvalidationCounts {
  const obj = (data ?? {}) as Record<string, unknown>
  const src = (obj.invalidated && typeof obj.invalidated === 'object'
    ? obj.invalidated
    : obj) as Record<string, unknown>
  return {
    l1: Number(src.l1 ?? 0),
    l2: Number(src.l2 ?? 0),
    node: typeof obj.node === 'string' ? obj.node : undefined,
  }
}

export async function listNegCache(
  params: NegCacheListParams,
  signal?: AbortSignal,
): Promise<NegCacheListResponse> {
  const { data } = await getApiClient().get('/admin/neg-cache', { params, signal })
  return {
    items: data.items ?? [],
    total: data.total ?? 0,
    page: data.page,
    pageSize: data.pageSize,
    source: data.source ?? 'L1-only',
    node: data.node ?? '',
  }
}

export async function probeNegCacheUrl(url: string): Promise<NegCacheProbeResponse> {
  const { data } = await getApiClient().get('/admin/neg-cache/probe', { params: { url } })
  return { keys: data.keys ?? [], shadowed: !!data.shadowed, node: data.node }
}

export async function invalidateNegCacheKey(key: NegCacheKey): Promise<NegCacheInvalidationCounts> {
  const { data } = await getApiClient().post('/admin/neg-cache/invalidate', {
    scope: key.scope,
    repoType: key.repoType,
    artifactName: key.artifactName,
    version: key.artifactVersion ?? '',
  })
  return toCounts(data)
}

export async function invalidateNegCachePackage(body: {
  artifactName: string
  repoType?: string
}): Promise<NegCacheInvalidationCounts> {
  const { data } = await getApiClient().post('/admin/neg-cache/invalidate-package', body)
  return toCounts(data)
}

export async function invalidateNegCachePattern(
  body: NegCachePatternBody,
): Promise<NegCacheInvalidationCounts> {
  const { data } = await getApiClient().post('/admin/neg-cache/invalidate-pattern', body)
  return toCounts(data)
}

export async function getNegCacheStats(): Promise<NegCacheStats> {
  const { data } = await getApiClient().get('/admin/neg-cache/stats')
  return { ...data, l2Size: data.l2Size ?? null, node: data.node ?? '' }
}
