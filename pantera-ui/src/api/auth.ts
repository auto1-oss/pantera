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
  state: string,
): Promise<TokenResponse> {
  // `state` binds this exchange to the login the server started: the
  // server consumes the nonce it issued for this state and requires the
  // id_token to carry it (2.2.9).
  const { data } = await getApiClient().post<TokenResponse>('/auth/callback', {
    code,
    provider,
    callback_url: callbackUrl,
    state,
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

// --- Security policy settings (2.2.9): request limits, egress, login throttle ---

/**
 * GET/PUT /admin/request-limits-settings. All values are strings, like
 * every other settings endpoint. `max_request_body_bytes` is the hard cap
 * on a single request body (bytes, >= 1 MiB); `fs_storage_roots` lists the
 * directories an inline `fs` repository storage path may live under
 * (path-separator delimited absolute paths).
 */
export interface RequestLimitsSettings {
  max_request_body_bytes: string
  fs_storage_roots: string
}

export async function getRequestLimitsSettings(): Promise<RequestLimitsSettings> {
  const { data } = await getApiClient().get<RequestLimitsSettings>(
    '/admin/request-limits-settings',
  )
  return data
}

export async function updateRequestLimitsSettings(
  settings: Partial<RequestLimitsSettings>,
): Promise<void> {
  await getApiClient().put('/admin/request-limits-settings', settings)
}

/**
 * GET/PUT /admin/egress-settings. `egress_block_private` ("true"/"false")
 * refuses outbound connections to private, loopback and link-local
 * destinations; `egress_allow_hosts` exempts listed hosts from that;
 * `upstream_credential_allow_hosts` lists hosts a bearer-token realm may
 * live on before upstream credentials are released to it. Comma-separated.
 */
export interface EgressSettings {
  egress_block_private: string
  egress_allow_hosts: string
  upstream_credential_allow_hosts: string
}

export async function getEgressSettings(): Promise<EgressSettings> {
  const { data } = await getApiClient().get<EgressSettings>('/admin/egress-settings')
  return data
}

export async function updateEgressSettings(settings: Partial<EgressSettings>): Promise<void> {
  await getApiClient().put('/admin/egress-settings', settings)
}

/**
 * GET/PUT /admin/login-throttle-settings. Failed password logins per
 * (user, client IP) before further attempts are refused, and the window
 * (seconds) those failures count in.
 */
export interface LoginThrottleSettings {
  login_throttle_max_failures: string
  login_throttle_window_seconds: string
}

export async function getLoginThrottleSettings(): Promise<LoginThrottleSettings> {
  const { data } = await getApiClient().get<LoginThrottleSettings>(
    '/admin/login-throttle-settings',
  )
  return data
}

export async function updateLoginThrottleSettings(
  settings: Partial<LoginThrottleSettings>,
): Promise<void> {
  await getApiClient().put('/admin/login-throttle-settings', settings)
}

// --- Client-Facing Base URL Settings ---

/**
 * Shape of the 3 tunables returned by GET /admin/client-base-url-settings.
 * These govern `ClientBaseUrl` (pantera-core) — the absolute-URL derivation
 * used for links Pantera emits (e.g. npm `dist.tarball`) when a repository
 * has no explicit `url:` configured. `client_base_host_allowlist` is a
 * comma-joined string of allowed `Host` values; an empty string is
 * PERMISSIVE (any `Host` is honoured — the default, matching pre-existing
 * behaviour so upgrading never breaks a deployment). `client_base_url` is
 * the canonical origin (+ optional path prefix, e.g.
 * `https://reg.example.com/artifactory`); when non-empty it is ENFORCED for
 * every repository without an explicit `url:` — `trust_forwarded_headers`
 * and `client_base_host_allowlist` stop being consulted for those repos
 * entirely. Empty string (the default) means unset.
 */
export interface ClientBaseUrlSettings {
  trust_forwarded_headers: string
  client_base_host_allowlist: string
  client_base_url: string
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
