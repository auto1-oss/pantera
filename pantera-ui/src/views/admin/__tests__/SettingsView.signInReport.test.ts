import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import SettingsView from '../SettingsView.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'

const getSignInReportSettingsMock = vi.fn()
const updateSignInReportSettingsMock = vi.fn()
const updateFailedLoginsSettingsMock = vi.fn()

vi.mock('@/api/signInFailures', () => ({
  getSignInReportSettings: (...a: unknown[]) => getSignInReportSettingsMock(...a),
  updateSignInReportSettings: (...a: unknown[]) => updateSignInReportSettingsMock(...a),
}))

vi.mock('@/api/failedLogins', () => ({
  getFailedLoginsSettings: () => Promise.resolve({}),
  updateFailedLoginsSettings: (...a: unknown[]) => updateFailedLoginsSettingsMock(...a),
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
  updateUpstreamBreakerSettings: vi.fn().mockResolvedValue(undefined),
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

const RETENTION_PROBLEM = 'Keep lines for (days) must be a whole number from 1 to 3650'

interface SignInReportVm {
  signInReportEnabled: boolean
  signInReportRetentionDays: number | null
  failedLoginsLockAfter: number | null
  saveAll: () => Promise<void>
}

let wrapper: VueWrapper | null = null

function signInAs(actions: string[]) {
  const auth = useAuthStore()
  auth.user = {
    name: 'admin', context: 'local',
    permissions: { api_user_permissions: actions },
  } as unknown as typeof auth.user
}

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

function vmOf(w: VueWrapper): SignInReportVm {
  return w.vm as unknown as SignInReportVm
}

function card(w: VueWrapper) {
  return w.find('[data-testid="sign-in-report-card"]')
}

function toasts() {
  return useNotificationStore().toasts
}

beforeEach(() => {
  setActivePinia(createPinia())
  signInAs(['read', 'create', 'update', 'delete', 'enable'])
  getSignInReportSettingsMock.mockReset().mockResolvedValue({
    sign_in_report_enabled: 'true', sign_in_report_retention_days: '90',
  })
  updateSignInReportSettingsMock.mockReset().mockResolvedValue(undefined)
  updateFailedLoginsSettingsMock.mockReset().mockResolvedValue(undefined)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('SettingsView: Sign-in report card', () => {
  it('loads both values of the server into the card', async () => {
    getSignInReportSettingsMock.mockResolvedValue({
      sign_in_report_enabled: 'false', sign_in_report_retention_days: '30',
    })

    const w = await mountView()
    const vm = vmOf(w)

    expect(
      { enabled: vm.signInReportEnabled, days: vm.signInReportRetentionDays },
      'a switch and a number from the strings of the server',
    ).toEqual({ enabled: false, days: 30 })
    expect(
      w.find('[data-testid="settings-save-bar"]').exists(),
      'loaded values are the baseline: nothing is dirty',
    ).toBe(false)
  })

  it('reads a missing switch value as on', async () => {
    getSignInReportSettingsMock.mockResolvedValue({ sign_in_report_retention_days: '90' })

    const w = await mountView()

    expect(vmOf(w).signInReportEnabled).toBe(true)
  })

  it('keeps its defaults when the settings cannot be loaded', async () => {
    getSignInReportSettingsMock.mockRejectedValue({ response: { status: 500, data: {} } })

    const w = await mountView()
    const vm = vmOf(w)

    expect(
      { enabled: vm.signInReportEnabled, days: vm.signInReportRetentionDays },
      'the defaults of the server',
    ).toEqual({ enabled: true, days: 90 })
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'nothing is dirty').toBe(false)
  })

  it('labels both settings and says what switching the report off does', async () => {
    const w = await mountView()
    const text = card(w).text()

    for (const label of ['Sign-in report', 'Record sign-in failures', 'Keep lines for (days)']) {
      expect(text, `label "${label}"`).toContain(label)
    }
    expect(text, 'what off does').toContain(
      'Off = nothing is noted or stored. Lines already stored stay until their retention ends.',
    )
    expect(text, 'the range of the retention').toContain('1 to 3650')
  })

  it('stands after the Failed logins card', async () => {
    const w = await mountView()
    const failedLogins = w.find('[data-testid="failed-logins-card"]').element
    const report = card(w).element

    expect(
      failedLogins.compareDocumentPosition(report) & Node.DOCUMENT_POSITION_FOLLOWING,
      'the report card follows',
    ).toBeTruthy()
  })

  it('links to the Sign-in failures page', async () => {
    const w = await mountView()
    const link = card(w).find('[data-testid="sign-in-report-link"]')

    expect(link.attributes('data-to'), 'where the report is').toBe('/admin/sign-in-failures')
    expect(link.text(), 'named as the page is').toContain('Sign-in failures')
  })

  it('names the page without a link for an account that may not read users', async () => {
    const auth = useAuthStore()
    auth.user = {
      name: 'admin', context: 'local',
      permissions: { api_role_permissions: ['read', 'create', 'update'] },
    } as unknown as typeof auth.user

    const w = await mountView()

    expect(card(w).find('[data-testid="sign-in-report-link"]').exists(), 'no link that leads nowhere')
      .toBe(false)
    expect(card(w).text(), 'the page is still named').toContain('Sign-in failures')
  })

  it('becomes dirty on its own, apart from Failed logins', async () => {
    const w = await mountView()

    vmOf(w).signInReportRetentionDays = 30
    await flushPromises()

    expect(w.find('[data-testid="save-bar-chip-sign_in_report"]').exists(), 'the card is dirty').toBe(true)
    expect(w.find('[data-testid="save-bar-chip-failed_logins"]').exists(), 'Failed logins is not').toBe(false)
    expect(
      w.find('[data-testid="section-pill-sign_in_report"]').text(),
      'the change applies without a restart',
    ).toContain('Hot reload')

    vmOf(w).signInReportRetentionDays = 90
    await flushPromises()
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'clean again at the loaded value').toBe(false)
  })

  it('becomes dirty when the switch is flipped', async () => {
    const w = await mountView()

    vmOf(w).signInReportEnabled = false
    await flushPromises()

    expect(w.find('[data-testid="save-bar-chip-sign_in_report"]').exists()).toBe(true)
  })

  it('saves only the retention when only the retention was changed', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.signInReportRetentionDays = 30
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(updateSignInReportSettingsMock, 'one request').toHaveBeenCalledTimes(1)
    expect(updateSignInReportSettingsMock.mock.calls[0][0], 'the changed key, as a string').toEqual({
      sign_in_report_retention_days: '30',
    })
    expect(updateFailedLoginsSettingsMock, 'Failed logins is not saved with it').not.toHaveBeenCalled()
    expect(
      w.find('[data-testid="settings-save-bar"]').exists(),
      'the saved value is the new baseline',
    ).toBe(false)
  })

  it('saves only the switch when only the switch was flipped', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.signInReportEnabled = false
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(updateSignInReportSettingsMock.mock.calls[0][0]).toEqual({ sign_in_report_enabled: 'false' })
  })

  it('saves both keys when both were changed', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.signInReportEnabled = false
    vm.signInReportRetentionDays = 3650
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(updateSignInReportSettingsMock.mock.calls[0][0]).toEqual({
      sign_in_report_enabled: 'false', sign_in_report_retention_days: '3650',
    })
  })

  it('sends a second save only what changed since the first', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.signInReportRetentionDays = 30
    await flushPromises()
    await vm.saveAll()
    await flushPromises()

    vm.signInReportEnabled = false
    await flushPromises()
    await vm.saveAll()
    await flushPromises()

    expect(updateSignInReportSettingsMock, 'two requests').toHaveBeenCalledTimes(2)
    expect(updateSignInReportSettingsMock.mock.calls[1][0], 'the retention is not sent again')
      .toEqual({ sign_in_report_enabled: 'false' })
  })

  it.each([
    [0], [3651], [-5], [2.5], [null],
  ] as const)('does not save a retention of %s days', async (days) => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.signInReportRetentionDays = days
    await flushPromises()

    expect(
      w.find('[data-testid="sign-in-report-problem"]').text(),
      'the card says what is wrong while editing',
    ).toContain(RETENTION_PROBLEM)

    await vm.saveAll()
    await flushPromises()

    expect(updateSignInReportSettingsMock, 'nothing is sent').not.toHaveBeenCalled()
    expect(
      toasts().some(t => t.severity === 'error' && t.summary === RETENTION_PROBLEM),
      'the save reports the reason',
    ).toBe(true)
    expect(
      w.find('[data-testid="save-bar-chip-sign_in_report"]').exists(),
      'the edit is kept',
    ).toBe(true)
  })

  it.each([[1], [3650]] as const)('saves the bound of %s days', async (days) => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.signInReportRetentionDays = days
    await flushPromises()

    expect(w.find('[data-testid="sign-in-report-problem"]').exists(), 'no problem shown').toBe(false)
    await vm.saveAll()
    await flushPromises()

    expect(updateSignInReportSettingsMock.mock.calls[0][0]).toEqual({
      sign_in_report_retention_days: String(days),
    })
  })

  it('shows the message of a 400 from the server and keeps the card dirty', async () => {
    updateSignInReportSettingsMock.mockRejectedValue({
      response: {
        status: 400,
        data: {
          error: 'BAD_REQUEST',
          message: 'Invalid sign-in-report setting: sign_in_report_retention_days must be between 1 and 3650',
          status: 400,
        },
      },
    })
    const w = await mountView()
    const vm = vmOf(w)
    vm.signInReportRetentionDays = 45
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'error'
        && t.detail === 'Invalid sign-in-report setting: sign_in_report_retention_days must be between 1 and 3650'),
      'the server message is shown',
    ).toBe(true)
    expect(
      w.find('[data-testid="save-bar-chip-sign_in_report"]').exists(),
      'the rejected card stays dirty',
    ).toBe(true)
    expect(vm.signInReportRetentionDays, 'the edit is kept').toBe(45)
    expect(
      toasts().some(t => t.severity === 'success' && t.summary.startsWith('Saved')),
      'no success is reported',
    ).toBe(false)
  })

  it('sends the same change again after a failed save', async () => {
    updateSignInReportSettingsMock.mockRejectedValueOnce(new Error('Network Error'))
    const w = await mountView()
    const vm = vmOf(w)
    vm.signInReportRetentionDays = 45
    await flushPromises()
    await vm.saveAll()
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(updateSignInReportSettingsMock, 'a second request').toHaveBeenCalledTimes(2)
    expect(updateSignInReportSettingsMock.mock.calls[1][0], 'with the change that was not saved')
      .toEqual({ sign_in_report_retention_days: '45' })
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'now nothing is dirty').toBe(false)
  })

  it('saves beside another card, each with its own request', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.signInReportEnabled = false
    vm.failedLoginsLockAfter = 5
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(updateSignInReportSettingsMock, 'the report').toHaveBeenCalledTimes(1)
    expect(updateFailedLoginsSettingsMock, 'the failed logins').toHaveBeenCalledTimes(1)
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'both are saved').toBe(false)
  })

  it('Discard restores the loaded values', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.signInReportEnabled = false
    vm.signInReportRetentionDays = 7
    await flushPromises()

    await w.find('[data-testid="settings-discard"]').trigger('click')
    await flushPromises()

    expect(
      { enabled: vm.signInReportEnabled, days: vm.signInReportRetentionDays },
      'the values of the server are back',
    ).toEqual({ enabled: true, days: 90 })
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'nothing is dirty').toBe(false)
    expect(updateSignInReportSettingsMock, 'nothing was sent').not.toHaveBeenCalled()
  })
})
