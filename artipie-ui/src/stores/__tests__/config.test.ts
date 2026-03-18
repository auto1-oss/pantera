import { describe, it, expect, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useConfigStore } from '../config'

describe('configStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('has default values', () => {
    const store = useConfigStore()
    expect(store.apiBaseUrl).toBe('/api/v1')
    expect(store.defaultPageSize).toBe(20)
  })

  it('loads runtime config', () => {
    const store = useConfigStore()
    store.loadConfig({
      apiBaseUrl: '/custom/api',
      grafanaUrl: 'http://grafana:3000',
      appTitle: 'Test',
      defaultPageSize: 50,
    })
    expect(store.apiBaseUrl).toBe('/custom/api')
    expect(store.appTitle).toBe('Test')
    expect(store.defaultPageSize).toBe(50)
  })
})
