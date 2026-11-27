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
  getDockerSettings: () => Promise.resolve({ tag_refresh_seconds: 3600 }),
  updateDockerSettings: vi.fn().mockResolvedValue(undefined),
  getClientBaseUrlSettings: () => Promise.resolve({}),
  updateClientBaseUrlSettings: vi.fn().mockResolvedValue(undefined),
  getRequestLimitsSettings: () => Promise.resolve({}),
  updateRequestLimitsSettings: vi.fn().mockResolvedValue(undefined),
  getEgressSettings: () => Promise.resolve({}),
  updateEgressSettings: vi.fn().mockResolvedValue(undefined),
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
  failed_logins_second_factor_limit: '7',
  failed_logins_second_factor_pause_minutes: '30',
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
  failedLoginsSecondFactorLimit: number | null
  failedLoginsSecondFactorPauseMinutes: number | null
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
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('SettingsView: Failed logins card, the policy', () => {
  it('loads the ten values of the server into the card', async () => {
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
      secondFactorLimit: vm.failedLoginsSecondFactorLimit,
      pause: vm.failedLoginsSecondFactorPauseMinutes,
    }, 'numbers and switches from the strings of the server').toEqual({
      from: 4, first: 2, max: 120, lock: 5, unlock: 30, forget: 60, keys: true, all: false,
      secondFactorLimit: 7, pause: 30,
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
      'Pause after unconfirmed sign-ins',
      'Pause (minutes)',
    ]) {
      expect(text, `label "${label}"`).toContain(label)
    }
    for (const help of [
      '0 = no delay',
      '0 = no lock; saving 0 unlocks every locked user within 15 seconds',
      '0 = only an administrator unlocks',
      '0 = never',
      'On = password, API keys, sessions and the provider button; off = password sign-in only.',
      'Default: 5. From this unconfirmed sign-in on, password sign-in of the user is paused and no push is sent. 0 = never pause.',
      'Default: 15. Unconfirmed sign-ins count while each comes within this time of the previous one. A successful sign-in ends the count.',
    ]) {
      expect(text, `help "${help}"`).toContain(help)
    }
  })

  it('says what it counts, for every way of signing in', async () => {
    const w = await mountView()

    expect(card(w).find('.p-card-subtitle').text().replace(/\s+/g, ' ')).toBe(
      'Failed sign-ins are counted per user name, on every node, for every way of signing in: '
      + 'the sign-in form, the token API and repository clients. A wrong password and an '
      + 'unconfirmed second factor are counted apart. Stored in the database; the '
      + 'PANTERA_FAILED_LOGINS_* environment variables are only the fallback.',
    )
  })

  it('tells at a glance what is a wrong password and what is an unconfirmed second factor', async () => {
    const w = await mountView()
    const counted = card(w).find('[data-testid="failed-logins-counted"]')
    const words = (el: { text: () => string }) => el.text().replace(/\s+/g, ' ')

    expect(counted.text(), 'the title').toContain('What is counted')
    expect(
      counted.findAll('dt').map(dt => words(dt)),
      'the three kinds, in this order',
    ).toEqual(['Wrong password', 'Second factor not confirmed', 'Not counted'])
    expect(counted.findAll('dd').map(dd => words(dd)), 'what each one is and does').toEqual([
      'Counts toward the delay and the lock.',
      'The password was right; the push was denied or not answered, or the code was wrong. '
        + 'Counts toward the pause; never a wrong password, never locks.',
      'The identity provider cannot be reached, the account is locked or its password expired '
        + 'at the provider, a repository client was asked for a second factor it cannot give.',
    ])
  })

  it('groups the settings under wrong password, second factor and lock scope, in that order', async () => {
    const w = await mountView()
    const c = card(w)
    const headings = c.findAll('h4').map(h => h.text())
    const section = (id: string) => c.find(`[data-testid="failed-logins-${id}"]`)

    expect(headings, 'the sub-headings').toEqual(['Wrong password', 'Second factor not confirmed', 'Lock scope'])
    for (const label of [
      'Delay logins from failed login number', 'Lock after failed logins', 'First delay (seconds)',
      'Longest delay (seconds)', 'Unlock automatically after (minutes)', 'Forget failures after (minutes)',
      'Count rejected API keys',
    ]) {
      expect(section('wrong-password').text(), `"${label}" is about a wrong password`).toContain(label)
    }
    expect(section('second-factor').text(), 'the limit of the pause').toContain('Pause after unconfirmed sign-ins')
    expect(section('second-factor').text(), 'the length of the pause').toContain('Pause (minutes)')
    expect(section('second-factor').text(), 'nothing of the lock').not.toContain('Lock after')
    expect(section('lock-scope').text(), 'the scope of a lock').toContain('A lock blocks every credential')
    const order = ['counted', 'wrong-password', 'second-factor', 'lock-scope']
      .map(id => section(id).element)
    for (let i = 1; i < order.length; i++) {
      expect(
        order[i - 1].compareDocumentPosition(order[i]) & Node.DOCUMENT_POSITION_FOLLOWING,
        `${i}: in order`,
      ).toBeTruthy()
    }
    const lockedUsers = c.find('[data-testid="locked-users-refresh"]').element
    expect(
      section('lock-scope').element.compareDocumentPosition(lockedUsers) & Node.DOCUMENT_POSITION_FOLLOWING,
      'the locked users stay at the end of the card',
    ).toBeTruthy()
  })

  it('becomes dirty on its own', async () => {
    const w = await mountView()

    vmOf(w).failedLoginsLockAfter = 10
    await flushPromises()

    expect(w.find('[data-testid="save-bar-chip-failed_logins"]').exists(), 'the card is dirty').toBe(true)
    expect(w.find('[data-testid="save-bar-chip-egress"]').exists(), 'the card before it is not').toBe(false)
    expect(w.find('[data-testid="save-bar-chip-sign_in_report"]').exists(), 'the card after it is not').toBe(false)
    expect(
      w.find('[data-testid="section-pill-failed_logins"]').text(),
      'the change applies without a restart',
    ).toContain('Hot reload')

    vmOf(w).failedLoginsLockAfter = 5
    await flushPromises()
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'clean again at the loaded value').toBe(false)
  })

  it.each([
    ['failedLoginsSecondFactorLimit', 0, 7],
    ['failedLoginsSecondFactorPauseMinutes', 60, 30],
  ] as const)('becomes dirty when %s changes, and clean again at the loaded value', async (field, edited, loaded) => {
    const w = await mountView()
    const vm = vmOf(w) as unknown as Record<string, number | null>

    vm[field] = edited
    await flushPromises()
    expect(w.find('[data-testid="save-bar-chip-failed_logins"]').exists(), 'dirty').toBe(true)

    vm[field] = loaded
    await flushPromises()
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'clean').toBe(false)
  })

  it('becomes dirty when a switch is flipped', async () => {
    const w = await mountView()

    vmOf(w).failedLoginsLockAllCredentials = true
    await flushPromises()

    expect(w.find('[data-testid="save-bar-chip-failed_logins"]').exists()).toBe(true)
  })

  it('saves all ten values as strings', async () => {
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
    vm.failedLoginsSecondFactorLimit = 0
    vm.failedLoginsSecondFactorPauseMinutes = 1440
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
      failed_logins_second_factor_limit: '0',
      failed_logins_second_factor_pause_minutes: '1440',
    })
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
    ['failedLoginsSecondFactorLimit', 101, 'Pause after unconfirmed sign-ins must be a whole number from 0 to 100'],
    ['failedLoginsSecondFactorLimit', -1, 'Pause after unconfirmed sign-ins must be a whole number from 0 to 100'],
    ['failedLoginsSecondFactorLimit', 2.5, 'Pause after unconfirmed sign-ins must be a whole number from 0 to 100'],
    ['failedLoginsSecondFactorLimit', null, 'Pause after unconfirmed sign-ins must be a whole number from 0 to 100'],
    ['failedLoginsSecondFactorPauseMinutes', 0, 'Pause (minutes) must be a whole number from 1 to 1440'],
    ['failedLoginsSecondFactorPauseMinutes', 1441, 'Pause (minutes) must be a whole number from 1 to 1440'],
    ['failedLoginsSecondFactorPauseMinutes', null, 'Pause (minutes) must be a whole number from 1 to 1440'],
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
    ['failedLoginsSecondFactorLimit', 0],
    ['failedLoginsSecondFactorLimit', 100],
    ['failedLoginsSecondFactorPauseMinutes', 1],
    ['failedLoginsSecondFactorPauseMinutes', 1440],
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
    vm.failedLoginsSecondFactorLimit = 0
    vm.failedLoginsSecondFactorPauseMinutes = 1
    await flushPromises()

    await w.find('[data-testid="settings-discard"]').trigger('click')
    await flushPromises()

    expect({
      lock: vm.failedLoginsLockAfter,
      max: vm.failedLoginsMaxDelaySeconds,
      keys: vm.failedLoginsCountRejectedKeys,
      all: vm.failedLoginsLockAllCredentials,
      secondFactorLimit: vm.failedLoginsSecondFactorLimit,
      pause: vm.failedLoginsSecondFactorPauseMinutes,
    }, 'the values of the server are back').toEqual({
      lock: 5, max: 120, keys: true, all: false, secondFactorLimit: 7, pause: 30,
    })
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
      secondFactorLimit: vm.failedLoginsSecondFactorLimit,
      pause: vm.failedLoginsSecondFactorPauseMinutes,
    }, 'the defaults of the server').toEqual({
      from: 2, first: 1, max: 60, lock: 3, unlock: 0, forget: 0, keys: false, all: true,
      secondFactorLimit: 5, pause: 15,
    })
    expect(w.find('[data-testid="settings-save-bar"]').exists(), 'nothing is dirty').toBe(false)
  })

  it('takes the defaults of the second factor when the server does not send them', async () => {
    const {
      failed_logins_second_factor_limit: _limit,
      failed_logins_second_factor_pause_minutes: _pause,
      ...older
    } = SERVER_SETTINGS
    getFailedLoginsSettingsMock.mockResolvedValue(older)

    const w = await mountView()
    const vm = vmOf(w)

    expect(
      { limit: vm.failedLoginsSecondFactorLimit, pause: vm.failedLoginsSecondFactorPauseMinutes },
      'the defaults of the server',
    ).toEqual({ limit: 5, pause: 15 })
    expect(vm.failedLoginsLockAfter, 'the other values are loaded').toBe(5)
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
