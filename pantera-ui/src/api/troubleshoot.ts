import { getApiClient } from './client'

/** The server fetches the URL in-process; allow for slow upstreams. */
const TROUBLESHOOT_TIMEOUT_MS = 60_000

export type CheckStatus = 'ok' | 'problem' | 'info'
export type CheckLayer =
  | 'repository'
  | 'negative-cache'
  | 'cooldown'
  | 'metadata'
  | 'group'
  | 'upstream'

export interface TroubleshootFix {
  action: string
  endpoint: string
  body?: Record<string, unknown>
}

export interface TroubleshootCheck {
  id: string
  layer: CheckLayer | string
  status: CheckStatus
  message: string
  fix?: TroubleshootFix | null
}

export interface TroubleshootResponse {
  url: string
  repo: { name: string; type: string; mode: string; members?: string[] } | null
  parsed: { package?: string | null; version?: string | null; kind?: 'artifact' | 'metadata' | string } | null
  request: {
    status: number
    headers?: Record<string, string>
    bodySnippet?: string
  } | null
  checks: TroubleshootCheck[]
  node: string
}

export async function troubleshootUrl(url: string): Promise<TroubleshootResponse> {
  const { data } = await getApiClient().get('/admin/troubleshoot', {
    params: { url },
    timeout: TROUBLESHOOT_TIMEOUT_MS,
  })
  return data
}

/**
 * Map a fix endpoint onto the API client. The server may give it with or
 * without the `/api/v1` prefix; the client's baseURL already carries it.
 * Absolute URLs are refused so a check can only drive this API.
 */
export function fixPath(endpoint: string): string {
  if (/^[a-z][a-z0-9+.-]*:/i.test(endpoint) || endpoint.startsWith('//')) {
    throw new Error(`Refusing non-relative fix endpoint: ${endpoint}`)
  }
  const path = endpoint.startsWith('/') ? endpoint : `/${endpoint}`
  return path.replace(/^\/api\/v1(?=\/)/, '')
}

export async function runTroubleshootFix(fix: TroubleshootFix): Promise<unknown> {
  const { data } = await getApiClient().post(fixPath(fix.endpoint), fix.body ?? {}, {
    timeout: TROUBLESHOOT_TIMEOUT_MS,
  })
  return data
}
