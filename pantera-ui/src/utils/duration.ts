/**
 * Human-readable duration for cache TTLs and ages, e.g. `23h 59m`,
 * `4m 10s`, `850ms`. Null/undefined renders as an em dash.
 */
export function formatDurationMs(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return '—'
  if (ms < 0) return 'no expiry'
  if (ms < 1000) return `${Math.round(ms)}ms`
  const secs = Math.floor(ms / 1000)
  const days = Math.floor(secs / 86400)
  const hours = Math.floor((secs % 86400) / 3600)
  const mins = Math.floor((secs % 3600) / 60)
  const rem = secs % 60
  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`
  if (hours > 0) return mins > 0 ? `${hours}h ${mins}m` : `${hours}h`
  if (mins > 0) return rem > 0 ? `${mins}m ${rem}s` : `${mins}m`
  return `${rem}s`
}
