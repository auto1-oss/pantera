/**
 * Message to show for a failed admin API call: the server's own
 * `message` (the REST API returns `{ error, message }` on 4xx/5xx, e.g.
 * a password-policy or validation rejection), else the transport error,
 * else the caller's fallback.
 *
 * Only for admin/management screens, where the server message is the
 * actionable detail. Sign-in flows use `formatAuthError`, which never
 * echoes server text.
 */
export function apiErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object') {
    const candidate = err as { response?: { data?: unknown }; message?: unknown }
    const data = candidate.response?.data
    if (data && typeof data === 'object') {
      const msg = (data as { message?: unknown }).message
      if (typeof msg === 'string' && msg.trim().length > 0) return msg
    }
    if (!candidate.response && typeof candidate.message === 'string' && candidate.message.length > 0) {
      return candidate.message
    }
  }
  return fallback
}
