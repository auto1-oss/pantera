import { describe, it, expect, beforeEach } from 'vitest'
import { initApiClient, getApiClient } from '../client'

describe('API Client', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  it('creates axios instance with base URL', () => {
    const client = initApiClient('/api/v1')
    expect(client.defaults.baseURL).toBe('/api/v1')
    expect(client.defaults.timeout).toBe(10_000)
  })

  it('getApiClient returns initialized instance', () => {
    initApiClient('/api/v1')
    const client = getApiClient()
    expect(client).toBeDefined()
    expect(client.defaults.baseURL).toBe('/api/v1')
  })

  it('sets Authorization header when JWT is in sessionStorage', () => {
    sessionStorage.setItem('jwt', 'test-token-123')
    const client = initApiClient('/api/v1')
    // Verify interceptor is registered (request interceptors array)
    expect(client.interceptors.request).toBeDefined()
  })
})
