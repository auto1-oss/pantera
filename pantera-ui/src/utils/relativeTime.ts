/** "3 days ago" style label for an ISO timestamp; empty string for none. */
export function relativeTime(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return ''
  const diff = Math.max(0, now.getTime() - new Date(iso).getTime())
  const seconds = Math.floor(diff / 1000)
  if (seconds < 60) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return plural(minutes, 'minute')
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return plural(hours, 'hour')
  const days = Math.floor(hours / 24)
  if (days < 30) return plural(days, 'day')
  const months = Math.floor(days / 30)
  if (months < 12) return plural(months, 'month')
  return plural(Math.floor(days / 365), 'year')
}

function plural(n: number, unit: string): string {
  return `${n} ${unit}${n === 1 ? '' : 's'} ago`
}
