import axios, {
  type AxiosInstance,
  type InternalAxiosRequestConfig,
  type AxiosRequestConfig,
} from 'axios'

/**
 * Axios config shape extended with a private retry marker.
 * Used by the response interceptor to guarantee that any single
 * request is only retried once after a silent token refresh.
 * Without this guard, an endpoint that legitimately returns 401
 * for reasons other than session expiry (e.g. a password-change
 * endpoint rejecting the current password) would loop the
 * interceptor forever: refresh succeeds, retry fires, 401 again,
 * interceptor queues, never resolves.
 */
interface RetryableRequestConfig extends AxiosRequestConfig {
  _retriedAfterRefresh?: boolean
}

let apiClient: AxiosInstance | null = null

/** True while a /auth/refresh call is in-flight — prevents parallel refresh storms. */
let isRefreshing = false

/** Queued request resolvers waiting for the in-flight refresh to complete. */
let pendingRefreshQueue: Array<(newToken: string) => void> = []

/** Endpoints that must NOT trigger a silent refresh on 401 (they are the auth boundary). */
const AUTH_BYPASS_URLS = ['/auth/token', '/auth/providers', '/auth/callback', '/auth/refresh']

function flushPendingQueue(newToken: string) {
  pendingRefreshQueue.forEach((resolve) => resolve(newToken))
  pendingRefreshQueue = []
}

function redirectToLogin() {
  localStorage.removeItem('access_token')
  localStorage.removeItem('refresh_token')
  localStorage.removeItem('jwt')
  window.location.href = '/login'
}

export function initApiClient(baseUrl: string): AxiosInstance {
  apiClient = axios.create({
    baseURL: baseUrl,
    timeout: 10_000,
    headers: { 'Content-Type': 'application/json' },
  })
  // Trace propagation: link UI actions to backend spans.
  // If Elastic APM RUM is active, use its traceparent. Otherwise generate one.
  apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const apm = (window as unknown as Record<string, unknown>).__ELASTIC_APM as
        { getCurrentTransaction?: () => { traceparent?: string } | null } | undefined
    if (apm?.getCurrentTransaction) {
      const tx = apm.getCurrentTransaction()
      if (tx?.traceparent) {
        config.headers.traceparent = tx.traceparent
        return config
      }
    }
    // Fallback: generate a traceparent so backend logs correlate with UI actions
    // even when APM is disabled. Format: 00-{traceId32}-{spanId16}-01
    const hex = (n: number) =>
      Array.from(crypto.getRandomValues(new Uint8Array(n)))
        .map(b => b.toString(16).padStart(2, '0')).join('')
    config.headers.traceparent = `00-${hex(16)}-${hex(8)}-01`
    return config
  })
  apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('access_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })
  apiClient.interceptors.response.use(
    (response) => response,
    async (error) => {
      if (error.response?.status !== 401) {
        return Promise.reject(error)
      }
      const url: string = error.config?.url ?? ''
      // Auth-boundary endpoints are the ones the user hits BEFORE they
      // have a session (login, providers list, SSO callback, refresh).
      // A 401 from these is not a session-expiry signal — it means the
      // attempt itself failed and the calling view owns the error UX.
      // We must NOT force a redirect here, otherwise a failed login on
      // the /login page triggers a full reload that wipes the inline
      // error banner before the user ever sees it.
      if (AUTH_BYPASS_URLS.some((bypass) => url.includes(bypass))) {
        return Promise.reject(error)
      }
      // Retry guard: if this request has already been retried once
      // after a silent refresh and STILL came back 401, the 401 is
      // not a session-expiry signal — it's an application-level
      // authorization failure for the operation itself (e.g. a
      // password-change endpoint rejecting the current password).
      // Propagate the error to the caller instead of looping the
      // interceptor forever.
      const cfg = error.config as RetryableRequestConfig
      if (cfg._retriedAfterRefresh) {
        return Promise.reject(error)
      }
      // If a refresh is already in-flight, queue this request to retry after it completes
      if (isRefreshing) {
        return new Promise<unknown>((resolve, reject) => {
          pendingRefreshQueue.push((newToken: string) => {
            error.config.headers.Authorization = `Bearer ${newToken}`
            cfg._retriedAfterRefresh = true
            apiClient!.request(error.config).then(resolve).catch(reject)
          })
        })
      }
      // First 401 — attempt silent refresh
      isRefreshing = true
      const refreshToken = localStorage.getItem('refresh_token')
      if (!refreshToken) {
        redirectToLogin()
        return Promise.reject(error)
      }
      try {
        const resp = await apiClient!.post<{ token: string; refresh_token?: string }>(
          '/auth/refresh',
          {},
          { headers: { Authorization: `Bearer ${refreshToken}` } },
        )
        const newToken = resp.data.token
        localStorage.setItem('access_token', newToken)
        if (resp.data.refresh_token) {
          localStorage.setItem('refresh_token', resp.data.refresh_token)
        }
        flushPendingQueue(newToken)
        // Retry the original failed request with the new token.
        // Mark it first so that if the retry STILL returns 401 we
        // propagate instead of looping back through the refresh path.
        error.config.headers.Authorization = `Bearer ${newToken}`
        cfg._retriedAfterRefresh = true
        return apiClient!.request(error.config)
      } catch {
        // Refresh itself failed — token is truly expired or revoked
        pendingRefreshQueue = []
        redirectToLogin()
        return Promise.reject(error)
      } finally {
        isRefreshing = false
      }
    },
  )
  return apiClient
}

export function getApiClient(): AxiosInstance {
  if (!apiClient) {
    throw new Error('API client not initialized. Call initApiClient() first.')
  }
  return apiClient
}
