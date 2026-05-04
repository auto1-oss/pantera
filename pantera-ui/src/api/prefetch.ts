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
 * PATCH /api/v1/repositories/:name with a settings.prefetch toggle.
 *
 * Body shape: {@code {"settings": {"prefetch": <boolean>}}}.
 * The server merges the body into the existing JSONB config — other
 * fields (storage, remotes, members, cooldown) are left untouched.
 */
export async function patchPrefetchEnabled(
  name: string,
  enabled: boolean,
): Promise<void> {
  await getApiClient().patch(`/repositories/${name}`, {
    settings: { prefetch: enabled },
  })
}
