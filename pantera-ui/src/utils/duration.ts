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

const SHORT = /^(\d+)([dhm])$/
const ISO = /^P(?:(\d+)D|T(\d+)H|T(\d+)M)$/

/**
 * Cooldown window as the UI writes it (`30d`, `12h`, `45m`) from the ISO-8601
 * form a repository config stores (`P30D`, `PT12H`, `PT45M`). Any other ISO
 * value is returned unchanged so it stays editable.
 */
export function isoToShortDuration(iso: string): string {
  const m = ISO.exec(iso.trim().toUpperCase())
  if (!m) return iso.trim()
  if (m[1]) return `${Number(m[1])}d`
  if (m[2]) return `${Number(m[2])}h`
  return `${Number(m[3])}m`
}

/**
 * ISO-8601 duration for a repository config from `30d` / `12h` / `45m`; an
 * ISO value is passed through. Null when the text is neither.
 */
export function shortToIsoDuration(text: string): string | null {
  const t = text.trim()
  const m = SHORT.exec(t.toLowerCase())
  if (m) {
    const n = Number(m[1])
    if (n <= 0) return null
    if (m[2] === 'd') return `P${n}D`
    return m[2] === 'h' ? `PT${n}H` : `PT${n}M`
  }
  return /^P(?:\d+[YMWD])*(?:T(?:\d+[HMS])+)?$/i.test(t) && t.length > 1 && !/T$/i.test(t)
    ? t.toUpperCase()
    : null
}
