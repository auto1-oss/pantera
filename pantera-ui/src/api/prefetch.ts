import { getApiClient } from './client'

/**
 * Per-repo prefetch stats over the last 24h. Mirrors the JSON
 * shape returned by {@code GET /api/v1/repositories/:name/prefetch/stats}
 * (server: PrefetchStatsHandler, Task 22).
 *
 * The {@code last_fetch_at} field is omitted by the server when no
 * successful prefetch has been recorded, so it is optional here too.
 */
export interface PrefetchStats {
  repo: string
  window: string
  prefetched: number
  cooldown_blocked: number
  dropped_queue_full: number
  dropped_semaphore_saturated: number
  dropped_dedup_in_flight: number
  dropped_circuit_open: number
  last_fetch_at?: string
}

/**
 * GET /api/v1/repositories/:name/prefetch/stats
 *
 * Returns 24h prefetch counters for the given repo. Counters return 0
 * when the prefetch subsystem is not wired (e.g. DB not configured).
 */
export async function getPrefetchStats(name: string): Promise<PrefetchStats> {
  const { data } = await getApiClient().get<PrefetchStats>(
    `/repositories/${name}/prefetch/stats`,
  )
  return data
}

/**
 * Toggle {@code settings.prefetch} on a repository via the existing
 * PUT /api/v1/repositories/:name endpoint (server has no PATCH route —
 * see RepositoryHandler.register, Task 21 commit b9bf92d63).
 *
 * The server stores the body verbatim (generic JSONB upsert), so a
 * partial body would clobber storage/remotes/members. We therefore do
 * a read-modify-write:
 *
 *   1. GET /repositories/:name           — full envelope
 *   2. mutate envelope.repo.settings.prefetch
 *   3. PUT /repositories/:name           — full envelope back
 *
 * The body shape on the wire is exactly what the server's PUT
 * round-trip test expects:
 * {@code {"repo": {"type": "...", "storage": ..., "settings": {"prefetch": <bool>}, ...}}}.
 */
export async function setPrefetchEnabled(
  name: string,
  enabled: boolean,
): Promise<void> {
  const client = getApiClient()
  const { data } = await client.get<Record<string, unknown>>(
    `/repositories/${name}`,
  )
  // The handler returns either {repo: {...}} or the bare repo object
  // depending on the CRUD impl. Normalise to the envelope shape PUT
  // requires.
  const envelope: Record<string, unknown> = (
    data && typeof data === 'object' && 'repo' in data
      ? data
      : { repo: data ?? {} }
  ) as Record<string, unknown>
  const repo = (envelope.repo ?? {}) as Record<string, unknown>
  const settings = (repo.settings ?? {}) as Record<string, unknown>
  settings.prefetch = enabled
  repo.settings = settings
  envelope.repo = repo
  await client.put(`/repositories/${name}`, envelope)
}
