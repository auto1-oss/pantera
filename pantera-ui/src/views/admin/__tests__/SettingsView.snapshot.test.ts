import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import SettingsView from '../SettingsView.vue'

// Mock the settings / auth APIs the view uses on mount.
const updateCooldownMock = vi.fn().mockResolvedValue(undefined)

vi.mock('@/api/settings', () => ({
  getSettings: () => Promise.resolve({
    port: 8080,
    version: '2.2.0',
    prefixes: [],
    jwt: { expires: true, expiry_seconds: 3600 },
    http_client: {
      proxy_timeout: 60,
      connection_timeout: 15000,
      idle_timeout: 30000,
      follow_redirects: true,
      connection_acquire_timeout: 30000,
      max_connections_per_destination: 64,
      max_requests_queued_per_destination: 256,
    },
    http_server: { request_timeout: 'PT2M' },
  }),
  getCooldownConfig: () => Promise.resolve({
    enabled: true,
    minimum_allowed_age: '7d',
    snapshots: { enabled: true, minimum_allowed_age: '14d' },
  }),
  updatePrefixes: vi.fn().mockResolvedValue(undefined),
  updateSettingsSection: vi.fn().mockResolvedValue(undefined),
  updateCooldownConfig: (...args: unknown[]) => updateCooldownMock(...args),
}))

vi.mock('@/api/auth', () => ({
  getAuthSettings: () => Promise.resolve({}),
  updateAuthSettings: vi.fn().mockResolvedValue(undefined),
  getCircuitBreakerSettings: () => Promise.resolve({}),
  updateCircuitBreakerSettings: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/composables/useRuntimeSettings', async () => {
  const { ref, reactive } = await import('vue')
  const fakeRow = () => ref({ key: '', value: 0, default: 0, source: 'default' })
  return {
    useRuntimeSettings: () => ({
      load: () => Promise.resolve(),
      loading: ref(false),
      loadError: ref(''),
      rows: {
        'http_client.bulkhead.adaptive': fakeRow(),
        'http_client.bulkhead.min_permits': fakeRow(),
        'http_client.bulkhead.max_permits': fakeRow(),
        'http_client.bulkhead.initial_permits': fakeRow(),
        'http_client.bulkhead.target_p99_ms': fakeRow(),
        'http_client.bulkhead.window_seconds': fakeRow(),
        'http_client.bulkhead.ramp_up_step': fakeRow(),
        'http_client.bulkhead.ramp_down_factor': fakeRow(),
      },
      edited: reactive({
        'http_client.bulkhead.adaptive': false,
        'http_client.bulkhead.min_permits': 1,
        'http_client.bulkhead.max_permits': 1,
        'http_client.bulkhead.initial_permits': 1,
        'http_client.bulkhead.target_p99_ms': 1,
        'http_client.bulkhead.window_seconds': 1,
        'http_client.bulkhead.ramp_up_step': 1,
        'http_client.bulkhead.ramp_down_factor': 0.5,
      }),
      isDirty: () => false,
      isOverridden: () => false,
      saving: reactive({}),
      saveOne: vi.fn(),
      saveAllDirty: vi.fn(),
      resetOne: vi.fn(),
    }),
  }
})

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

function mountView() {
  return mount(SettingsView, {
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: {
        'router-link': true,
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
}

describe('SettingsView — SNAPSHOT cooldown section', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    updateCooldownMock.mockReset()
  })

  it('renders the SNAPSHOT cooldown sub-section', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-testid="snapshot-cooldown-section"]').exists()).toBe(true)
  })

  it('pre-loads the SNAPSHOT fields from the cooldown config', async () => {
    const wrapper = mountView()
    await flushPromises()
    const select = wrapper.find('[data-testid="snapshot-enabled-select"]')
      .element as HTMLSelectElement
    expect(select).toBeTruthy()
    expect(select.value).toBe('true')
    const age = wrapper.find('[data-testid="snapshot-age-input"]')
      .element as HTMLInputElement
    expect(age.value).toBe('14d')
  })

  it('renders the inline help text explaining SNAPSHOT scope', async () => {
    const wrapper = mountView()
    await flushPromises()
    const section = wrapper.find('[data-testid="snapshot-cooldown-section"]')
    expect(section.text()).toContain('SNAPSHOT')
    expect(section.text()).toContain('Maven/Gradle')
  })
})
