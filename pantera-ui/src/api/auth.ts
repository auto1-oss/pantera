import { getApiClient } from './client'
import type { AuthProvidersResponse, TokenResponse, UserInfo } from '@/types'

export async function getProviders(): Promise<AuthProvidersResponse> {
  const { data } = await getApiClient().get<AuthProvidersResponse>('/auth/providers')
  return data
}

export async function login(name: string, pass: string): Promise<TokenResponse> {
  const { data } = await getApiClient().post<TokenResponse>('/auth/token', { name, pass })
  return data
}

export async function getMe(): Promise<UserInfo> {
  const { data } = await getApiClient().get<UserInfo>('/auth/me')
  return data
}

export async function getProviderRedirect(
  name: string,
  callbackUrl: string,
): Promise<{ url: string; state: string }> {
  const { data } = await getApiClient().get<{ url: string; state: string }>(
    `/auth/providers/${name}/redirect`,
    { params: { callback_url: callbackUrl } },
  )
  return data
}

export interface GenerateTokenResponse {
  token: string
  id: string
  label: string
  expires_at?: string
  permanent: boolean
}

/**
 * Generate an API token for the currently authenticated user.
 * Uses the session JWT — no password required.
 */
export async function generateTokenForSession(
  expiryDays = 30,
  label = 'API Token',
): Promise<GenerateTokenResponse> {
  const { data } = await getApiClient().post<GenerateTokenResponse>('/auth/token/generate', {
    expiry_days: expiryDays,
    label,
  })
  return data
}

export interface ApiToken {
  id: string
  label: string
  created_at: string
  expires_at?: string
  expired?: boolean
  permanent?: boolean
}

export async function listTokens(): Promise<ApiToken[]> {
  const { data } = await getApiClient().get<{ tokens: ApiToken[] }>('/auth/tokens')
  return data.tokens
}

export async function revokeToken(tokenId: string): Promise<void> {
  await getApiClient().delete(`/auth/tokens/${tokenId}`)
}

export async function exchangeOAuthCode(
  code: string,
  provider: string,
  callbackUrl: string,
): Promise<TokenResponse> {
  const { data } = await getApiClient().post<TokenResponse>('/auth/callback', {
    code,
    provider,
    callback_url: callbackUrl,
  })
  return data
}

/**
 * Exchange the current (still-valid) session JWT for a fresh one with a full expiry window.
 * Called silently by the Axios 401 interceptor before falling back to full logout.
 */
export async function refreshToken(): Promise<TokenResponse> {
  const { data } = await getApiClient().post<TokenResponse>('/auth/refresh')
  return data
}

// --- Admin Auth Settings ---

export async function getAuthSettings(): Promise<Record<string, string>> {
  const { data } = await getApiClient().get<Record<string, string>>('/admin/auth-settings')
  return data
}

export async function updateAuthSettings(settings: Record<string, string>): Promise<void> {
  await getApiClient().put('/admin/auth-settings', settings)
}

// --- Circuit Breaker Settings ---

/**
 * Shape of the 5 circuit-breaker tunables returned by
 * GET /admin/circuit-breaker-settings. All values are strings (same
 * convention as auth-settings); the form parses them to numbers for
 * validation before submit.
 */
export interface CircuitBreakerSettings {
  circuit_breaker_failure_rate_threshold: string
  circuit_breaker_minimum_number_of_calls: string
  circuit_breaker_sliding_window_seconds: string
  circuit_breaker_initial_block_seconds: string
  circuit_breaker_max_block_seconds: string
}

export async function getCircuitBreakerSettings(): Promise<CircuitBreakerSettings> {
  const { data } = await getApiClient().get<CircuitBreakerSettings>(
    '/admin/circuit-breaker-settings',
  )
  return data
}

export async function updateCircuitBreakerSettings(
  settings: Partial<CircuitBreakerSettings>,
): Promise<void> {
  await getApiClient().put('/admin/circuit-breaker-settings', settings)
}

// --- Upstream HTTP Breaker Settings ---

/**
 * Shape of the 5 upstream-breaker tunables returned by
 * GET /admin/upstream-breaker-settings. All values are strings (same
 * convention as circuit-breaker-settings); the form parses them to
 * numbers for validation before submit.
 *
 * NOT the same breaker as {@link CircuitBreakerSettings}: that one is
 * the GROUP-MEMBER breaker, which blocks a member repository during
 * group resolution after sustained failures. This one is the OUTBOUND
 * HTTP-client breaker — it blocks outbound calls per upstream
 * `scheme://host:port` after sustained 5xx/connection failures and
 * recovers via a HEAD probe with Fibonacci backoff.
 */
export interface UpstreamBreakerSettings {
  upstream_breaker_failure_rate_threshold: string
  upstream_breaker_minimum_calls: string
  upstream_breaker_window_seconds: string
  upstream_breaker_seed_backoff_seconds: string
  upstream_breaker_max_backoff_seconds: string
}

export async function getUpstreamBreakerSettings(): Promise<UpstreamBreakerSettings> {
  const { data } = await getApiClient().get<UpstreamBreakerSettings>(
    '/admin/upstream-breaker-settings',
  )
  return data
}

export async function updateUpstreamBreakerSettings(
  settings: Partial<UpstreamBreakerSettings>,
): Promise<void> {
  await getApiClient().put('/admin/upstream-breaker-settings', settings)
}

// --- Client-Facing Base URL Settings ---

/**
 * Shape of the 2 tunables returned by GET /admin/client-base-url-settings.
 * These govern `ClientBaseUrl` (pantera-core) — the absolute-URL derivation
 * used for links Pantera emits (e.g. npm `dist.tarball`) when a repository
 * has no explicit `url:` configured. `client_base_host_allowlist` is a
 * comma-joined string of allowed `Host` values; an empty string is
 * PERMISSIVE (any `Host` is honoured — the default, matching pre-existing
 * behaviour so upgrading never breaks a deployment).
 */
export interface ClientBaseUrlSettings {
  trust_forwarded_headers: string
  client_base_host_allowlist: string
}

export async function getClientBaseUrlSettings(): Promise<ClientBaseUrlSettings> {
  const { data } = await getApiClient().get<ClientBaseUrlSettings>(
    '/admin/client-base-url-settings',
  )
  return data
}

export async function updateClientBaseUrlSettings(
  settings: Partial<ClientBaseUrlSettings>,
): Promise<void> {
  await getApiClient().put('/admin/client-base-url-settings', settings)
}

export async function revokeAllUserTokens(username: string): Promise<{ revoked_count: number }> {
  const { data } = await getApiClient().post<{ revoked_count: number }>(
    `/admin/revoke-user/${username}`
  )
  return data
}
