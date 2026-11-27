import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import SettingsView from '../SettingsView.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

const getDockerSettingsMock = vi.fn()
const updateDockerSettingsMock = vi.fn()
const updateUpstreamBreakerSettingsMock = vi.fn()

vi.mock('@/api/signInFailures', () => ({
  getSignInReportSettings: () => Promise.resolve({}),
  updateSignInReportSettings: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/api/failedLogins', () => ({
  getFailedLoginsSettings: () => Promise.resolve({}),
  updateFailedLoginsSettings: vi.fn().mockResolvedValue(undefined),
  listLockedUsers: () => Promise.resolve([]),
  unlockUser: vi.fn(),
  unlockAllUsers: vi.fn(),
}))

vi.mock('@/api/settings', () => ({
  getSettings: () => Promise.resolve({
    port: 8080,
    version: '2.2.15',
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
  getCooldownConfig: () => Promise.resolve({ enabled: true, minimum_allowed_age: '7d' }),
  updatePrefixes: vi.fn().mockResolvedValue(undefined),
  updateSettingsSection: vi.fn().mockResolvedValue(undefined),
  updateCooldownConfig: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/api/auth', () => ({
  getAuthSettings: () => Promise.resolve({}),
  updateAuthSettings: vi.fn().mockResolvedValue(undefined),
  getCircuitBreakerSettings: () => Promise.resolve({}),
  updateCircuitBreakerSettings: vi.fn().mockResolvedValue(undefined),
  getUpstreamBreakerSettings: () => Promise.resolve({}),
  updateUpstreamBreakerSettings: (...a: unknown[]) => updateUpstreamBreakerSettingsMock(...a),
  getDockerSettings: (...a: unknown[]) => getDockerSettingsMock(...a),
  updateDockerSettings: (...a: unknown[]) => updateDockerSettingsMock(...a),
  getClientBaseUrlSettings: () => Promise.resolve({}),
  updateClientBaseUrlSettings: vi.fn().mockResolvedValue(undefined),
  getRequestLimitsSettings: () => Promise.resolve({}),
  updateRequestLimitsSettings: vi.fn().mockResolvedValue(undefined),
  getEgressSettings: () => Promise.resolve({}),
  updateEgressSettings: vi.fn().mockResolvedValue(undefined),
  getLoginThrottleSettings: () => Promise.resolve({}),
  updateLoginThrottleSettings: vi.fn().mockResolvedValue(undefined),
}))

vi.mock('@/composables/useRuntimeSettings', async () => {
  const { ref, reactive, computed } = await import('vue')
  const fakeRow = () => ref({ key: '', value: 0, default: 0, source: 'default' })
  const keys = [
    'adaptive', 'min_permits', 'max_permits', 'initial_permits',
    'target_p99_ms', 'window_seconds', 'ramp_up_step', 'ramp_down_factor',
  ].map(k => `http_client.bulkhead.${k}`)
  return {
    useRuntimeSettings: () => ({
      load: () => Promise.resolve(),
      loading: ref(false),
      loadError: ref(''),
      rows: Object.fromEntries(keys.map(k => [k, fakeRow()])),
      edited: reactive(Object.fromEntries(keys.map(k => [k, 1]))),
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

const PROBLEM = 'Re-check a cached tag after (seconds) must be a whole number from 0 to 31536000'

interface DockerVm {
  dockerTagRefreshSeconds: number | null
  ubMinCalls: number
  saveAll: () => Promise<void>
}

let wrapper: VueWrapper | null = null

async function mountView() {
  wrapper = mount(SettingsView, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      directives: { tooltip: {} },
      stubs: {
        'router-link': { props: ['to'], template: '<a :data-to="to"><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

function vmOf(w: VueWrapper): DockerVm {
  return w.vm as unknown as DockerVm
}

function card(w: VueWrapper) {
  return w.find('[data-testid="docker-proxy-card"]')
}

function toasts() {
  return useNotificationStore().toasts
}

beforeEach(() => {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    name: 'admin', context: 'local',
    permissions: { api_user_permissions: ['read', 'create', 'update', 'delete', 'enable'] },
  } as unknown as typeof auth.user
  getDockerSettingsMock.mockReset().mockResolvedValue({ tag_refresh_seconds: 3600 })
  updateDockerSettingsMock.mockReset().mockResolvedValue(undefined)
  updateUpstreamBreakerSettingsMock.mockReset().mockResolvedValue(undefined)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('SettingsView: Docker Proxy Tags card', () => {
  it('loads the age of the server into the card', async () => {
    getDockerSettingsMock.mockResolvedValue({ tag_refresh_seconds: 600 })

    const w = await mountView()

    expect(vmOf(w).dockerTagRefreshSeconds, 'the number of the server').toBe(600)
    expect(
      w.find('[data-testid="settings-save-bar"]').exists(),
      'the loaded value is the baseline: nothing is dirty',
    ).toBe(false)
  })

  it('keeps the default of one hour when the setting cannot be loaded', async () => {
    getDockerSettingsMock.mockRejectedValue({ response: { status: 500, data: {} } })

    const w = await mountView()

    expect(vmOf(w).dockerTagRefreshSeconds, 'the default of the server').toBe(3600)
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'nothing is dirty').toBe(false)
  })

  it('names the card and the field and says what the age does', async () => {
    const w = await mountView()
    const text = card(w).text()

    for (const label of ['Docker Proxy Tags', 'Re-check a cached tag after (seconds)']) {
      expect(text, `label "${label}"`).toContain(label)
    }
    expect(text, 'what is checked').toContain('asks the upstream with a HEAD whether the tag moved')
    expect(text, 'what 0 does').toContain('0 re-checks on every request')
    expect(text, 'the repository override').toContain('A repository can override it')
  })

  it('becomes dirty on its own and applies without a restart', async () => {
    const w = await mountView()

    vmOf(w).dockerTagRefreshSeconds = 60
    await flushPromises()

    expect(w.find('[data-testid="save-bar-chip-docker_proxy"]').exists(), 'the card is dirty').toBe(true)
    expect(
      w.find('[data-testid="save-bar-chip-upstream_breaker"]').exists(),
      'the upstream breaker card is not',
    ).toBe(false)
    expect(
      w.find('[data-testid="section-pill-docker_proxy"]').text(),
      'the change applies without a restart',
    ).toContain('Hot reload')

    vmOf(w).dockerTagRefreshSeconds = 3600
    await flushPromises()
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'clean again at the loaded value').toBe(false)
  })

  it.each([[0], [60], [31536000]] as const)('saves an age of %s seconds as a number', async (seconds) => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.dockerTagRefreshSeconds = seconds
    await flushPromises()

    expect(w.find('[data-testid="docker-proxy-problem"]').exists(), 'no problem shown').toBe(false)
    await vm.saveAll()
    await flushPromises()

    expect(updateDockerSettingsMock, 'one request').toHaveBeenCalledTimes(1)
    expect(updateDockerSettingsMock.mock.calls[0][0]).toEqual({ tag_refresh_seconds: seconds })
    expect(updateUpstreamBreakerSettingsMock, 'no other card is saved').not.toHaveBeenCalled()
    expect(
      w.find('[data-testid="settings-save-bar"]').exists(),
      'the saved value is the new baseline',
    ).toBe(false)
  })

  it.each([[-1], [31536001], [2.5], [null]] as const)('does not save an age of %s', async (seconds) => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.dockerTagRefreshSeconds = seconds
    await flushPromises()

    expect(
      w.find('[data-testid="docker-proxy-problem"]').text(),
      'the card says what is wrong while editing',
    ).toContain(PROBLEM)

    await vm.saveAll()
    await flushPromises()

    expect(updateDockerSettingsMock, 'nothing is sent').not.toHaveBeenCalled()
    expect(
      toasts().some(t => t.severity === 'error' && t.summary === PROBLEM),
      'the save reports the reason',
    ).toBe(true)
    expect(
      w.find('[data-testid="save-bar-chip-docker_proxy"]').exists(),
      'the edit is kept',
    ).toBe(true)
  })

  it('shows the message of a 400 from the server and keeps the card dirty', async () => {
    updateDockerSettingsMock.mockRejectedValue({
      response: {
        status: 400,
        data: {
          error: 'BAD_REQUEST',
          message: 'Invalid docker setting: tag_refresh_seconds must be an integer from 0 to 31536000',
          status: 400,
        },
      },
    })
    const w = await mountView()
    const vm = vmOf(w)
    vm.dockerTagRefreshSeconds = 45
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'error'
        && t.detail === 'Invalid docker setting: tag_refresh_seconds must be an integer from 0 to 31536000'),
      'the server message is shown',
    ).toBe(true)
    expect(
      w.find('[data-testid="save-bar-chip-docker_proxy"]').exists(),
      'the rejected card stays dirty',
    ).toBe(true)
    expect(vm.dockerTagRefreshSeconds, 'the edit is kept').toBe(45)
  })

  it('saves beside another card, each with its own request', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.dockerTagRefreshSeconds = 120
    vm.ubMinCalls = 20
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(updateDockerSettingsMock, 'the docker card').toHaveBeenCalledTimes(1)
    expect(updateUpstreamBreakerSettingsMock, 'the upstream breaker card').toHaveBeenCalledTimes(1)
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'both are saved').toBe(false)
  })

  it('Discard restores the loaded value', async () => {
    getDockerSettingsMock.mockResolvedValue({ tag_refresh_seconds: 900 })
    const w = await mountView()
    const vm = vmOf(w)
    vm.dockerTagRefreshSeconds = 7
    await flushPromises()

    await w.find('[data-testid="settings-discard"]').trigger('click')
    await flushPromises()

    expect(vm.dockerTagRefreshSeconds, 'the value of the server is back').toBe(900)
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'nothing is dirty').toBe(false)
    expect(updateDockerSettingsMock, 'nothing was sent').not.toHaveBeenCalled()
  })
})
