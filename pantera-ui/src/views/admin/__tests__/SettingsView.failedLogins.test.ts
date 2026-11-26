import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import SettingsView from '../SettingsView.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import type { FailedLogins } from '@/types'

const getFailedLoginsSettingsMock = vi.fn()
const updateFailedLoginsSettingsMock = vi.fn()
const listLockedUsersMock = vi.fn()
const unlockUserMock = vi.fn()
const unlockAllUsersMock = vi.fn()
const updateLoginThrottleMock = vi.fn()

vi.mock('@/api/failedLogins', () => ({
  getFailedLoginsSettings: (...a: unknown[]) => getFailedLoginsSettingsMock(...a),
  updateFailedLoginsSettings: (...a: unknown[]) => updateFailedLoginsSettingsMock(...a),
  listLockedUsers: (...a: unknown[]) => listLockedUsersMock(...a),
  unlockUser: (...a: unknown[]) => unlockUserMock(...a),
  unlockAllUsers: (...a: unknown[]) => unlockAllUsersMock(...a),
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
  getLoginThrottleSettings: () => Promise.resolve({
    login_throttle_max_failures: '5', login_throttle_window_seconds: '900',
  }),
  updateLoginThrottleSettings: (...a: unknown[]) => updateLoginThrottleMock(...a),
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

/** What the server answers for the policy: every value a string. */
const SERVER_SETTINGS = {
  failed_logins_delay_from_failure: '4',
  failed_logins_first_delay_seconds: '2',
  failed_logins_max_delay_seconds: '120',
  failed_logins_lock_after: '5',
  failed_logins_unlock_after_minutes: '30',
  failed_logins_forget_after_minutes: '60',
  failed_logins_count_rejected_keys: 'true',
  failed_logins_lock_all_credentials: 'false',
}

const ALICE: FailedLogins = {
  name: 'alice@example.com', locked: true, failures: 3,
  locked_at: '2026-01-01T10:00:04.099Z', last_failure_at: '2026-01-01T10:00:04.099Z',
  client_ip: '192.0.2.7', user_agent: 'Apache-Maven/3.9.9', path: 'repository', credential: 'password',
}
/** A locked name that belongs to no Pantera user, with no origin recorded. */
const STRANGER: FailedLogins = {
  name: 'no-such-user', locked: true, failures: 7,
  locked_at: '2026-01-02T08:00:00Z', last_failure_at: null,
  client_ip: '', user_agent: '', path: '', credential: '',
}

interface FailedLoginsVm {
  failedLoginsDelayFromFailure: number | null
  failedLoginsFirstDelaySeconds: number | null
  failedLoginsMaxDelaySeconds: number | null
  failedLoginsLockAfter: number | null
  failedLoginsUnlockAfterMinutes: number | null
  failedLoginsForgetAfterMinutes: number | null
  failedLoginsCountRejectedKeys: boolean
  failedLoginsLockAllCredentials: boolean
  loginThrottleMaxFailures: number
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
      stubs: { 'router-link': true },
    },
  })
  await flushPromises()
  return wrapper
}

function vmOf(w: VueWrapper): FailedLoginsVm {
  return w.vm as unknown as FailedLoginsVm
}

function card(w: VueWrapper) {
  return w.find('[data-testid="failed-logins-card"]')
}

function inBody<T extends HTMLElement = HTMLElement>(testid: string): T | null {
  return document.querySelector(`[data-testid="${testid}"]`) as T | null
}

function toasts() {
  return useNotificationStore().toasts
}

beforeEach(() => {
  setActivePinia(createPinia())
  signInAs(['read', 'create', 'update', 'delete', 'enable'])
  getFailedLoginsSettingsMock.mockReset().mockResolvedValue({ ...SERVER_SETTINGS })
  updateFailedLoginsSettingsMock.mockReset().mockResolvedValue(undefined)
  listLockedUsersMock.mockReset().mockResolvedValue([])
  unlockUserMock.mockReset().mockResolvedValue(true)
  unlockAllUsersMock.mockReset().mockResolvedValue([])
  updateLoginThrottleMock.mockReset().mockResolvedValue(undefined)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('SettingsView: Failed logins card, the policy', () => {
  it('loads the eight values of the server into the card', async () => {
    const w = await mountView()
    const vm = vmOf(w)

    expect({
      from: vm.failedLoginsDelayFromFailure,
      first: vm.failedLoginsFirstDelaySeconds,
      max: vm.failedLoginsMaxDelaySeconds,
      lock: vm.failedLoginsLockAfter,
      unlock: vm.failedLoginsUnlockAfterMinutes,
      forget: vm.failedLoginsForgetAfterMinutes,
      keys: vm.failedLoginsCountRejectedKeys,
      all: vm.failedLoginsLockAllCredentials,
    }, 'numbers and switches from the strings of the server').toEqual({
      from: 4, first: 2, max: 120, lock: 5, unlock: 30, forget: 60, keys: true, all: false,
    })
    expect(
      w.find('[data-testid="settings-save-bar"]').exists(),
      'loaded values are the baseline: nothing is dirty',
    ).toBe(false)
  })

  it('labels every setting as the documentation does and says what 0 means', async () => {
    const w = await mountView()
    const text = card(w).text()

    for (const label of [
      'Failed logins',
      'Delay logins from failed login number',
      'First delay (seconds)',
      'Longest delay (seconds)',
      'Lock after failed logins',
      'Unlock automatically after (minutes)',
      'Forget failures after (minutes)',
      'Count rejected API keys',
      'A lock blocks every credential',
    ]) {
      expect(text, `label "${label}"`).toContain(label)
    }
    for (const help of [
      '0 = no delay',
      '0 = no lock; saving 0 unlocks every locked user within 15 seconds',
      '0 = only an administrator unlocks',
      '0 = never',
      'On = password, API keys, sessions and the provider button; off = password sign-in only.',
    ]) {
      expect(text, `help "${help}"`).toContain(help)
    }
  })

  it('cannot be confused with the Login Throttling card', async () => {
    const w = await mountView()

    expect(w.text(), 'the older card keeps its name').toContain('Login Throttling')
    expect(w.text(), 'the older card says what it is and which limit comes first').toContain(
      'This is the sign-in form\'s own limit: with the default values the lock of the "Failed logins" card below comes first.',
    )
    expect(card(w).text(), 'the new card points at the older one')
      .toContain('Not the "Login Throttling" card above')
  })

  it('becomes dirty on its own, apart from Login Throttling', async () => {
    const w = await mountView()

    vmOf(w).failedLoginsLockAfter = 10
    await flushPromises()

    expect(w.find('[data-testid="save-bar-chip-failed_logins"]').exists(), 'the card is dirty').toBe(true)
    expect(w.find('[data-testid="save-bar-chip-login_throttle"]').exists(), 'Login Throttling is not').toBe(false)
    expect(
      w.find('[data-testid="section-pill-failed_logins"]').text(),
      'the change applies without a restart',
    ).toContain('Hot reload')

    vmOf(w).failedLoginsLockAfter = 5
    await flushPromises()
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'clean again at the loaded value').toBe(false)
  })

  it('becomes dirty when a switch is flipped', async () => {
    const w = await mountView()

    vmOf(w).failedLoginsLockAllCredentials = true
    await flushPromises()

    expect(w.find('[data-testid="save-bar-chip-failed_logins"]').exists()).toBe(true)
  })

  it('saves all eight values as strings', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.failedLoginsDelayFromFailure = 0
    vm.failedLoginsFirstDelaySeconds = 3
    vm.failedLoginsMaxDelaySeconds = 3600
    vm.failedLoginsLockAfter = 0
    vm.failedLoginsUnlockAfterMinutes = 1440
    vm.failedLoginsForgetAfterMinutes = 43200
    vm.failedLoginsCountRejectedKeys = false
    vm.failedLoginsLockAllCredentials = true
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(updateFailedLoginsSettingsMock, 'one request').toHaveBeenCalledTimes(1)
    expect(updateFailedLoginsSettingsMock.mock.calls[0][0], 'every value a string').toEqual({
      failed_logins_delay_from_failure: '0',
      failed_logins_first_delay_seconds: '3',
      failed_logins_max_delay_seconds: '3600',
      failed_logins_lock_after: '0',
      failed_logins_unlock_after_minutes: '1440',
      failed_logins_forget_after_minutes: '43200',
      failed_logins_count_rejected_keys: 'false',
      failed_logins_lock_all_credentials: 'true',
    })
    expect(updateLoginThrottleMock, 'Login Throttling is not saved with it').not.toHaveBeenCalled()
    expect(
      w.find('[data-testid="settings-save-bar"]').exists(),
      'the saved values are the new baseline',
    ).toBe(false)
  })

  it('does not save a longest delay below the first delay', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.failedLoginsFirstDelaySeconds = 30
    vm.failedLoginsMaxDelaySeconds = 10
    await flushPromises()

    expect(
      w.find('[data-testid="failed-logins-problem"]').text(),
      'the card says what is wrong while editing',
    ).toContain('Longest delay must not be below the first delay')

    await vm.saveAll()
    await flushPromises()

    expect(updateFailedLoginsSettingsMock, 'nothing is sent').not.toHaveBeenCalled()
    expect(
      toasts().some(t => t.severity === 'error' && t.summary === 'Longest delay must not be below the first delay'),
      'the save reports the reason',
    ).toBe(true)
    expect(
      w.find('[data-testid="save-bar-chip-failed_logins"]').exists(),
      'the edits are kept',
    ).toBe(true)
  })

  it('accepts a longest delay equal to the first delay', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.failedLoginsFirstDelaySeconds = 60
    vm.failedLoginsMaxDelaySeconds = 60
    await flushPromises()

    expect(w.find('[data-testid="failed-logins-problem"]').exists(), 'no problem shown').toBe(false)
    await vm.saveAll()
    await flushPromises()

    expect(updateFailedLoginsSettingsMock, 'the pair is saved').toHaveBeenCalledTimes(1)
  })

  it.each([
    ['failedLoginsDelayFromFailure', 101, 'Delay logins from failed login number must be a whole number from 0 to 100'],
    ['failedLoginsDelayFromFailure', -1, 'Delay logins from failed login number must be a whole number from 0 to 100'],
    ['failedLoginsFirstDelaySeconds', 0, 'First delay (seconds) must be a whole number from 1 to 60'],
    ['failedLoginsFirstDelaySeconds', 61, 'First delay (seconds) must be a whole number from 1 to 60'],
    ['failedLoginsMaxDelaySeconds', 3601, 'Longest delay (seconds) must be a whole number from 1 to 3600'],
    ['failedLoginsLockAfter', 101, 'Lock after failed logins must be a whole number from 0 to 100'],
    ['failedLoginsLockAfter', 2.5, 'Lock after failed logins must be a whole number from 0 to 100'],
    ['failedLoginsLockAfter', null, 'Lock after failed logins must be a whole number from 0 to 100'],
    ['failedLoginsUnlockAfterMinutes', 1441, 'Unlock automatically after (minutes) must be a whole number from 0 to 1440'],
    ['failedLoginsForgetAfterMinutes', 43201, 'Forget failures after (minutes) must be a whole number from 0 to 43200'],
  ] as const)('does not save %s = %s', async (field, value, problem) => {
    const w = await mountView()
    const vm = vmOf(w)
    ;(vm as unknown as Record<string, number | null>)[field] = value
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(updateFailedLoginsSettingsMock, 'nothing is sent').not.toHaveBeenCalled()
    expect(
      toasts().some(t => t.severity === 'error' && t.summary === problem),
      `the save reports "${problem}"`,
    ).toBe(true)
  })

  it.each([
    ['failedLoginsDelayFromFailure', 100],
    ['failedLoginsLockAfter', 100],
    ['failedLoginsUnlockAfterMinutes', 0],
    ['failedLoginsForgetAfterMinutes', 43200],
  ] as const)('saves the bound %s = %s', async (field, value) => {
    const w = await mountView()
    const vm = vmOf(w)
    ;(vm as unknown as Record<string, number | null>)[field] = value
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(updateFailedLoginsSettingsMock).toHaveBeenCalledTimes(1)
  })

  it('Discard restores the loaded values', async () => {
    const w = await mountView()
    const vm = vmOf(w)
    vm.failedLoginsLockAfter = 0
    vm.failedLoginsMaxDelaySeconds = 1
    vm.failedLoginsCountRejectedKeys = false
    vm.failedLoginsLockAllCredentials = true
    await flushPromises()

    await w.find('[data-testid="settings-discard"]').trigger('click')
    await flushPromises()

    expect({
      lock: vm.failedLoginsLockAfter,
      max: vm.failedLoginsMaxDelaySeconds,
      keys: vm.failedLoginsCountRejectedKeys,
      all: vm.failedLoginsLockAllCredentials,
    }, 'the values of the server are back').toEqual({ lock: 5, max: 120, keys: true, all: false })
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'nothing is dirty').toBe(false)
    expect(updateFailedLoginsSettingsMock, 'nothing was sent').not.toHaveBeenCalled()
  })

  it('shows the message of a 400 from the server and keeps the edits', async () => {
    updateFailedLoginsSettingsMock.mockRejectedValue({
      response: {
        status: 400,
        data: {
          error: 'BAD_REQUEST',
          message: 'Invalid failed-logins setting: failed_logins_lock_after must be between 0 and 100',
        },
      },
    })
    const w = await mountView()
    const vm = vmOf(w)
    vm.failedLoginsLockAfter = 7
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'error'
        && t.detail === 'Invalid failed-logins setting: failed_logins_lock_after must be between 0 and 100'),
      'the server message is shown',
    ).toBe(true)
    expect(
      w.find('[data-testid="save-bar-chip-failed_logins"]').exists(),
      'the rejected card stays dirty',
    ).toBe(true)
    expect(vm.failedLoginsLockAfter, 'the edit is kept').toBe(7)
    expect(
      toasts().some(t => t.severity === 'success' && t.summary.startsWith('Saved')),
      'no success is reported',
    ).toBe(false)
  })

  it('keeps its defaults when the policy cannot be loaded', async () => {
    getFailedLoginsSettingsMock.mockRejectedValue({ response: { status: 500, data: {} } })

    const w = await mountView()
    const vm = vmOf(w)

    expect({
      from: vm.failedLoginsDelayFromFailure,
      first: vm.failedLoginsFirstDelaySeconds,
      max: vm.failedLoginsMaxDelaySeconds,
      lock: vm.failedLoginsLockAfter,
      unlock: vm.failedLoginsUnlockAfterMinutes,
      forget: vm.failedLoginsForgetAfterMinutes,
      keys: vm.failedLoginsCountRejectedKeys,
      all: vm.failedLoginsLockAllCredentials,
    }, 'the defaults of the server').toEqual({
      from: 2, first: 1, max: 60, lock: 3, unlock: 0, forget: 0, keys: false, all: true,
    })
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'nothing is dirty').toBe(false)
  })
})

describe('SettingsView: Failed logins card, the locked users', () => {
  it('says that no user is locked', async () => {
    const w = await mountView()

    expect(listLockedUsersMock, 'the list is requested').toHaveBeenCalledTimes(1)
    expect(card(w).find('[data-testid="locked-users-empty"]').text(), 'the empty state')
      .toBe('No user is locked')
    expect(card(w).find('[data-testid="locked-users-unlock-all"]').exists(), 'nothing to unlock').toBe(false)
  })

  it('lists each locked name with the origin of its last failed login', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE, STRANGER])

    const w = await mountView()
    const rows = card(w).findAll('tbody tr')

    expect(rows.length, 'one row per locked name').toBe(2)
    const alice = rows[0].text()
    for (const part of ['alice@example.com', '3', '192.0.2.7', 'Apache-Maven/3.9.9', 'repository', 'password']) {
      expect(alice, `the row of alice shows "${part}"`).toContain(part)
    }
    expect(
      rows[0].find('[title="2026-01-01T10:00:04.099Z"]').exists(),
      'locked since carries the exact time',
    ).toBe(true)
    for (const header of [
      'Name', 'Locked since', 'Failed logins', 'Client address', 'Client program', 'Sign-in path', 'Credential',
    ]) {
      expect(card(w).find('thead').text(), `column "${header}"`).toContain(header)
    }
    expect(card(w).find('[data-testid="locked-users-count"]').text(), 'the number of locked names').toBe('(2)')
  })

  it('lists a locked name that is no Pantera user, with dashes for what is unknown', async () => {
    listLockedUsersMock.mockResolvedValue([STRANGER])

    const w = await mountView()
    const row = card(w).find('tbody tr')

    expect(row.find('[data-testid="locked-user-name"]').text(), 'the name').toBe('no-such-user')
    expect(row.text(), 'the count').toContain('7')
    expect(row.text().match(/—/g)?.length, 'address, program, path and credential are unknown').toBe(4)
    expect(row.find('[data-testid="locked-user-unlock"]').exists(), 'it can be unlocked').toBe(true)
  })

  it('pages a long list', async () => {
    listLockedUsersMock.mockResolvedValue(
      Array.from({ length: 25 }, (_, i) => ({ ...STRANGER, name: `name-${String(i).padStart(2, '0')}` })),
    )

    const w = await mountView()

    expect(card(w).find('[data-testid="locked-users-count"]').text(), 'every name is counted').toBe('(25)')
    expect(card(w).findAll('tbody tr').length, 'the first page').toBe(20)
    expect(card(w).find('.p-paginator').exists(), 'the rest is a page away').toBe(true)
  })

  it('shows no pager for a short list', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE, STRANGER])

    const w = await mountView()

    expect(card(w).find('.p-paginator').exists()).toBe(false)
  })

  it('unlocks one name and loads the list again', async () => {
    listLockedUsersMock.mockResolvedValueOnce([ALICE, STRANGER]).mockResolvedValue([STRANGER])
    const w = await mountView()

    await card(w).findAll('[data-testid="locked-user-unlock"]')[0].trigger('click')
    await flushPromises()

    expect(unlockUserMock, 'the name of the row, as the server gave it').toHaveBeenCalledWith('alice@example.com')
    expect(listLockedUsersMock, 'the list is loaded again').toHaveBeenCalledTimes(2)
    expect(card(w).findAll('tbody tr').length, 'the unlocked name is gone').toBe(1)
    expect(card(w).text(), 'the other name stays').toContain('no-such-user')
    expect(
      toasts().some(t => t.severity === 'success' && t.summary === 'User unlocked' && t.detail === 'alice@example.com'),
      'the unlock is confirmed',
    ).toBe(true)
  })

  it('shows the error of a failed unlock and keeps the row', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE])
    unlockUserMock.mockRejectedValue({
      response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } },
    })
    const w = await mountView()

    await card(w).find('[data-testid="locked-user-unlock"]').trigger('click')
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'error' && t.summary === 'Failed to unlock user' && t.detail === 'Access denied'),
      'the server message is shown',
    ).toBe(true)
    expect(card(w).findAll('tbody tr').length, 'the row stays').toBe(1)
  })

  it('unlocks all after a confirmation', async () => {
    listLockedUsersMock.mockResolvedValueOnce([ALICE, STRANGER]).mockResolvedValue([])
    unlockAllUsersMock.mockResolvedValue(['alice@example.com', 'no-such-user'])
    const w = await mountView()

    await card(w).find('[data-testid="locked-users-unlock-all"]').trigger('click')
    await flushPromises()

    expect(unlockAllUsersMock, 'nothing happens before the confirmation').not.toHaveBeenCalled()
    expect(inBody('unlock-all-dialog')?.textContent, 'the dialog names the number').toContain('Unlock 2 locked names?')

    inBody<HTMLButtonElement>('unlock-all-confirm')!.click()
    await flushPromises()

    expect(unlockAllUsersMock, 'one request').toHaveBeenCalledTimes(1)
    expect(listLockedUsersMock, 'the list is loaded again').toHaveBeenCalledTimes(2)
    expect(card(w).find('[data-testid="locked-users-empty"]').exists(), 'nobody is locked any more').toBe(true)
    expect(inBody('unlock-all-dialog'), 'the dialog is closed').toBeNull()
    expect(
      toasts().some(t => t.severity === 'success' && t.summary === 'Unlocked 2 users'),
      'the unlock is confirmed',
    ).toBe(true)
  })

  it('unlocks nobody when the confirmation is cancelled', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE])
    const w = await mountView()

    await card(w).find('[data-testid="locked-users-unlock-all"]').trigger('click')
    await flushPromises()
    inBody<HTMLButtonElement>('unlock-all-cancel')!.click()
    await flushPromises()

    expect(unlockAllUsersMock, 'no request').not.toHaveBeenCalled()
    expect(card(w).findAll('tbody tr').length, 'the row stays').toBe(1)
    expect(inBody('unlock-all-dialog'), 'the dialog is closed').toBeNull()
  })

  it('keeps the dialog open when unlocking all fails', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE])
    unlockAllUsersMock.mockRejectedValue({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })
    const w = await mountView()

    await card(w).find('[data-testid="locked-users-unlock-all"]').trigger('click')
    await flushPromises()
    inBody<HTMLButtonElement>('unlock-all-confirm')!.click()
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'error' && t.summary === 'Failed to unlock all users'),
      'the failure is shown',
    ).toBe(true)
    expect(inBody('unlock-all-dialog'), 'the dialog stays open').not.toBeNull()
    expect(card(w).findAll('tbody tr').length, 'the row stays').toBe(1)
  })

  it('offers no unlock without the permission to enable users', async () => {
    signInAs(['read', 'update'])
    listLockedUsersMock.mockResolvedValue([ALICE])

    const w = await mountView()

    expect(card(w).findAll('tbody tr').length, 'the list is still shown').toBe(1)
    expect(card(w).find('[data-testid="locked-user-unlock"]').exists(), 'no unlock per row').toBe(false)
    expect(card(w).find('[data-testid="locked-users-unlock-all"]').exists(), 'no unlock all').toBe(false)
  })

  it('does not ask for the list without the permission to read users', async () => {
    signInAs([])

    const w = await mountView()

    expect(listLockedUsersMock, 'no request that would be refused').not.toHaveBeenCalled()
    expect(card(w).find('[data-testid="locked-users-no-permission"]').exists(), 'the card says why').toBe(true)
  })

  it('says so when the list cannot be loaded and still shows the policy', async () => {
    listLockedUsersMock.mockRejectedValue({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })

    const w = await mountView()

    expect(card(w).find('[data-testid="locked-users-error"]').text(), 'the reason')
      .toContain('Internal server error')
    expect(card(w).find('[data-testid="locked-users-empty"]').exists(), 'not reported as "nobody locked"')
      .toBe(false)
    expect(vmOf(w).failedLoginsLockAfter, 'the policy is loaded').toBe(5)
  })

  it('loads the list again on Refresh', async () => {
    listLockedUsersMock.mockResolvedValueOnce([]).mockResolvedValue([ALICE])
    const w = await mountView()

    await card(w).find('[data-testid="locked-users-refresh"]').trigger('click')
    await flushPromises()

    expect(listLockedUsersMock, 'a second request').toHaveBeenCalledTimes(2)
    expect(card(w).findAll('tbody tr').length, 'the new lock is listed').toBe(1)
  })

  it('loads the list again after the policy was saved', async () => {
    listLockedUsersMock.mockResolvedValueOnce([ALICE]).mockResolvedValue([])
    const w = await mountView()
    const vm = vmOf(w)
    vm.failedLoginsLockAfter = 0
    await flushPromises()

    await vm.saveAll()
    await flushPromises()

    expect(listLockedUsersMock, 'a second request after the save').toHaveBeenCalledTimes(2)
    expect(card(w).find('[data-testid="locked-users-empty"]').exists(), 'the released locks are gone').toBe(true)
  })
})
