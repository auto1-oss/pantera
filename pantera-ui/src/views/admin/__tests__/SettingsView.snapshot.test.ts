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
  getUpstreamBreakerSettings: () => Promise.resolve({}),
  updateUpstreamBreakerSettings: vi.fn().mockResolvedValue(undefined),
  getClientBaseUrlSettings: () => Promise.resolve({}),
  getRequestLimitsSettings: () => Promise.resolve({}),
  updateRequestLimitsSettings: vi.fn().mockResolvedValue(undefined),
  getEgressSettings: () => Promise.resolve({}),
  updateEgressSettings: vi.fn().mockResolvedValue(undefined),
  getLoginThrottleSettings: () => Promise.resolve({}),
  updateLoginThrottleSettings: vi.fn().mockResolvedValue(undefined),
  updateClientBaseUrlSettings: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/composables/useRuntimeSettings', async () => {
  const { ref, reactive, computed } = await import('vue')
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
      // SettingsView's unified save bar reads anyDirty + isDirty to
      // know whether the bulkhead block is part of the dirty count;
      // mock both so the test doesn't blow up on undefined.value.
      anyDirty: computed(() => false),
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
      directives: {
        // PrimeVue's tooltip directive isn't registered in tests; a
        // no-op stub keeps the "Failed to resolve directive" warning
        // out of the output (the unified save bar pills use it).
        tooltip: {},
      },
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

describe('SettingsView — unified save bar', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    updateCooldownMock.mockReset()
  })

  it('is hidden when nothing is dirty', async () => {
    const wrapper = mountView()
    await flushPromises()
    // Baseline snapshot fires after onMounted; with no edits the save
    // bar must NOT be rendered (no transient flash from initial load).
    expect(wrapper.find('[data-testid="settings-save-bar"]').exists()).toBe(false)
  })

  it('shows the save bar as soon as a single hot-reload section becomes dirty', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as { cooldownAge: string }
    vm.cooldownAge = '30d'
    await flushPromises()
    const bar = wrapper.find('[data-testid="settings-save-bar"]')
    expect(bar.exists()).toBe(true)
    // The Cooldown chip should be present, marked as hot-reload (no
    // "restart" suffix, blue-styled).
    expect(wrapper.find('[data-testid="save-bar-chip-cooldown"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="save-bar-chip-cooldown"]').text())
      .not.toContain('restart')
  })

  it('shows the restart-required signal when a static section is edited', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as { httpServerTimeout: string }
    vm.httpServerTimeout = 'PT5M'
    await flushPromises()
    // The HTTP Server chip must carry the "restart" suffix because
    // SECTION_META marks the section hotReload=false.
    const chip = wrapper.find('[data-testid="save-bar-chip-http_server"]')
    expect(chip.exists()).toBe(true)
    expect(chip.text().toLowerCase()).toContain('restart')
    // The same section's title gets a Restart-required pill so the
    // signal is visible WHILE editing, not just at submit time.
    const pill = wrapper.find('[data-testid="section-pill-http_server"]')
    expect(pill.exists()).toBe(true)
    expect(pill.text()).toContain('Restart required')
  })

  it('tracks the upstream HTTP breaker as its own section, distinct from the group-member breaker', async () => {
    const wrapper = mountView()
    await flushPromises()
    // Both breaker cards render with their distinguishing labels.
    expect(wrapper.text()).toContain('Group Member Circuit Breaker')
    expect(wrapper.text()).toContain('Upstream HTTP Circuit Breaker')
    // Editing an upstream-breaker field dirties ONLY the new section.
    const vm = wrapper.vm as unknown as { ubMinCalls: number }
    vm.ubMinCalls = 42
    await flushPromises()
    expect(wrapper.find('[data-testid="save-bar-chip-upstream_breaker"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="save-bar-chip-circuit_breaker"]').exists()).toBe(false)
  })

  it('tracks the three 2.2.9 security policy cards as their own hot-reload sections', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('Request & Storage Limits')
    expect(wrapper.text()).toContain('Outbound Egress Policy')
    expect(wrapper.text()).toContain('Login Throttling')
    const vm = wrapper.vm as unknown as {
      maxRequestBodyMiB: number
      egressBlockPrivate: boolean
      loginThrottleMaxFailures: number
    }
    vm.maxRequestBodyMiB = 512
    await flushPromises()
    expect(wrapper.find('[data-testid="save-bar-chip-request_limits"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="save-bar-chip-egress"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="save-bar-chip-login_throttle"]').exists()).toBe(false)
    vm.egressBlockPrivate = true
    vm.loginThrottleMaxFailures = 3
    await flushPromises()
    expect(wrapper.find('[data-testid="save-bar-chip-egress"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="save-bar-chip-login_throttle"]').exists()).toBe(true)
    // All three apply on save without a restart: no restart pill.
    expect(wrapper.find('[data-testid="section-pill-egress"]').text()).not.toContain('Restart')
  })

  it('tracks the client-facing base URL settings as their own section', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('Client-Facing Base URL')
    const vm = wrapper.vm as unknown as { trustForwardedHeaders: boolean }
    vm.trustForwardedHeaders = true
    await flushPromises()
    expect(wrapper.find('[data-testid="save-bar-chip-client_base_url"]').exists()).toBe(true)
    // Hot-reload section: no "restart" suffix.
    expect(wrapper.find('[data-testid="save-bar-chip-client_base_url"]').text())
      .not.toContain('restart')
  })

  it('shows the permissive-allowlist warning until an allowlist entry is added', async () => {
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('PERMISSIVE')
    const vm = wrapper.vm as unknown as { clientBaseHostAllowlist: string }
    vm.clientBaseHostAllowlist = 'registry.example.com'
    await flushPromises()
    expect(wrapper.text()).not.toContain('PERMISSIVE')
  })

  it('clicking Discard reverts the edited section back to its baseline', async () => {
    const wrapper = mountView()
    await flushPromises()
    const vm = wrapper.vm as unknown as { cooldownAge: string }
    vm.cooldownAge = '99d'
    await flushPromises()
    expect(wrapper.find('[data-testid="settings-save-bar"]').exists()).toBe(true)
    await wrapper.find('[data-testid="settings-discard"]').trigger('click')
    await flushPromises()
    // Field returned to the baseline loaded by the api mock ('7d').
    expect((wrapper.vm as unknown as { cooldownAge: string }).cooldownAge).toBe('7d')
    // And the save bar hides because dirtyCount drops back to 0.
    expect(wrapper.find('[data-testid="settings-save-bar"]').exists()).toBe(false)
  })
})
