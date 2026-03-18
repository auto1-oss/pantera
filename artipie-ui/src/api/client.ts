import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'

let apiClient: AxiosInstance | null = null

export function initApiClient(baseUrl: string): AxiosInstance {
  apiClient = axios.create({
    baseURL: baseUrl,
    timeout: 10_000,
    headers: { 'Content-Type': 'application/json' },
  })
  apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    const token = sessionStorage.getItem('jwt')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  })
  apiClient.interceptors.response.use(
    (response) => response,
    (error) => {
      if (error.response?.status === 401) {
        // Don't logout on 401 from public auth endpoints (token generation, login)
        const url = error.config?.url ?? ''
        if (!url.includes('/auth/token') && !url.includes('/auth/providers') && !url.includes('/auth/callback')) {
          sessionStorage.removeItem('jwt')
          window.location.href = '/login'
        }
      }
      return Promise.reject(error)
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
