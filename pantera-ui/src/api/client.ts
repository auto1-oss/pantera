import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'

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
  localStorage.removeItem('jwt')
  window.location.href = '/login'
}

export function initApiClient(baseUrl: string): AxiosInstance {
  apiClient = axios.create({
    baseURL: baseUrl,
    timeout: 10_000,
    headers: { 'Content-Type': 'application/json' },
  })
  apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem('jwt')
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
      // Never attempt refresh for the auth boundary endpoints themselves
      if (AUTH_BYPASS_URLS.some((bypass) => url.includes(bypass))) {
        redirectToLogin()
        return Promise.reject(error)
      }
      // If a refresh is already in-flight, queue this request to retry after it completes
      if (isRefreshing) {
        return new Promise<unknown>((resolve) => {
          pendingRefreshQueue.push((newToken: string) => {
            error.config.headers.Authorization = `Bearer ${newToken}`
            resolve(apiClient!.request(error.config))
          })
        })
      }
      // First 401 — attempt silent refresh
      isRefreshing = true
      try {
        const resp = await apiClient!.post<{ token: string }>('/auth/refresh')
        const newToken = resp.data.token
        localStorage.setItem('jwt', newToken)
        flushPendingQueue(newToken)
        // Retry the original failed request with the new token
        error.config.headers.Authorization = `Bearer ${newToken}`
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
