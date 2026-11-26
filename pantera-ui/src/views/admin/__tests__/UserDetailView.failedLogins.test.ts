import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import UserDetailView from '../UserDetailView.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import type { FailedLogins } from '@/types'

const getUserMock = vi.fn()
const getFailedLoginsMock = vi.fn()
const unlockUserMock = vi.fn()
const pushMock = vi.fn()

vi.mock('@/api/users', () => ({
  getUser: (...a: unknown[]) => getUserMock(...a),
  putUser: vi.fn(),
  changePassword: vi.fn(),
  enableUser: vi.fn(),
  disableUser: vi.fn(),
}))

vi.mock('@/api/failedLogins', () => ({
  getFailedLogins: (...a: unknown[]) => getFailedLoginsMock(...a),
  unlockUser: (...a: unknown[]) => unlockUserMock(...a),
}))

vi.mock('@/api/roles', () => ({
  listRoles: () => Promise.resolve({ items: [{ name: 'readers' }], total: 1 }),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

const NAME = 'alice@example.com'

const LOCKED: FailedLogins = {
  name: NAME, locked: true, failures: 3,
  locked_at: '2026-01-01T10:00:04.099Z', last_failure_at: '2026-01-01T10:00:03.500Z',
  client_ip: '192.0.2.7', user_agent: 'Apache-Maven/3.9.9', path: 'repository', credential: 'password',
}
/** Failed logins below the lock limit: counted, not locked. */
const COUNTED: FailedLogins = {
  name: NAME, locked: false, failures: 1, locked_at: null,
  last_failure_at: '2026-11-26T17:52:27.588547Z',
  client_ip: '192.168.107.1', user_agent: 'curl/8.19.0', path: 'api', credential: 'password',
}
const CLEAN: FailedLogins = { name: NAME, failures: 0, locked: false }

let wrapper: VueWrapper | null = null

function signInAs(actions: string[]) {
  const auth = useAuthStore()
  auth.user = {
    name: 'admin', context: 'local',
    permissions: { api_user_permissions: actions },
  } as unknown as typeof auth.user
}

async function mountView(name = NAME) {
  wrapper = mount(UserDetailView, {
    props: { name },
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: { 'router-link': true },
    },
  })
  await flushPromises()
  return wrapper
}

function panel(w: VueWrapper) {
  return w.find('[data-testid="failed-logins-panel"]')
}

function field(w: VueWrapper, name: string): string {
  return panel(w).find(`[data-testid="failed-logins-${name}"]`).text()
}

function toasts() {
  return useNotificationStore().toasts
}

beforeEach(() => {
  setActivePinia(createPinia())
  signInAs(['read', 'update', 'enable'])
  getUserMock.mockReset().mockResolvedValue({ name: NAME, email: NAME, enabled: true, roles: [] })
  getFailedLoginsMock.mockReset().mockResolvedValue(CLEAN)
  unlockUserMock.mockReset().mockResolvedValue(true)
  pushMock.mockReset()
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('UserDetailView: Failed logins panel', () => {
  it('asks for the failed logins of the user of the page', async () => {
    await mountView()

    expect(getFailedLoginsMock).toHaveBeenCalledWith('alice@example.com')
  })

  it('shows a locked user with the lock and the origin of the last failed login', async () => {
    getFailedLoginsMock.mockResolvedValue(LOCKED)

    const w = await mountView()

    expect(panel(w).text(), 'the title').toContain('Failed logins')
    expect(field(w, 'state'), 'locked').toBe('Locked')
    expect(field(w, 'count'), 'the number of failed logins').toBe('Failed logins: 3')
    expect(
      panel(w).find('[data-testid="failed-logins-locked-at"] [title="2026-01-01T10:00:04.099Z"]').exists(),
      'locked since',
    ).toBe(true)
    expect(
      panel(w).find('[data-testid="failed-logins-last"] [title="2026-01-01T10:00:03.500Z"]').exists(),
      'time of the last failed login',
    ).toBe(true)
    expect(field(w, 'client-ip'), 'client address').toBe('Client address: 192.0.2.7')
    expect(field(w, 'user-agent'), 'client program').toBe('Client program: Apache-Maven/3.9.9')
    expect(field(w, 'path'), 'sign-in path').toBe('Sign-in path: repository')
    expect(field(w, 'credential'), 'credential').toBe('Credential: password')
    expect(panel(w).find('[data-testid="failed-logins-unlock"]').text(), 'the action').toBe('Unlock')
  })

  it('shows failed logins of a user who is not locked and offers to clear them', async () => {
    getFailedLoginsMock.mockResolvedValue(COUNTED)

    const w = await mountView()

    expect(field(w, 'state'), 'not locked').toBe('Not locked')
    expect(field(w, 'count'), 'the number of failed logins').toBe('Failed logins: 1')
    expect(field(w, 'locked-at'), 'no lock time').toBe('Locked since: —')
    expect(field(w, 'path'), 'sign-in path').toBe('Sign-in path: api')
    expect(panel(w).find('[data-testid="failed-logins-unlock"]').text(), 'the action')
      .toBe('Clear failed logins')
  })

  it('shows a user without failed logins and offers no action', async () => {
    const w = await mountView()

    expect(field(w, 'state'), 'not locked').toBe('Not locked')
    expect(field(w, 'count'), 'no failed login').toBe('Failed logins: 0')
    expect(panel(w).text(), 'said in words').toContain('No failed login is counted for this user.')
    expect(panel(w).find('[data-testid="failed-logins-unlock"]').exists(), 'nothing to unlock').toBe(false)
    expect(panel(w).find('[data-testid="failed-logins-client-ip"]').exists(), 'no origin to show').toBe(false)
  })

  it('shows dashes for an origin that was not recorded', async () => {
    getFailedLoginsMock.mockResolvedValue({
      ...LOCKED, last_failure_at: null, client_ip: '', user_agent: null, path: '', credential: undefined,
    })

    const w = await mountView()

    expect(field(w, 'last'), 'time').toBe('Last failed login: —')
    expect(field(w, 'client-ip'), 'client address').toBe('Client address: —')
    expect(field(w, 'user-agent'), 'client program').toBe('Client program: —')
    expect(field(w, 'path'), 'sign-in path').toBe('Sign-in path: —')
    expect(field(w, 'credential'), 'credential').toBe('Credential: —')
  })

  it('does not report an ended lock as a lock', async () => {
    // The lock time is still recorded, but the policy no longer keeps the lock.
    getFailedLoginsMock.mockResolvedValue({ ...LOCKED, locked: false })

    const w = await mountView()

    expect(field(w, 'state'), 'not locked').toBe('Not locked')
    expect(field(w, 'locked-at'), 'no lock time').toBe('Locked since: —')
    expect(panel(w).find('[data-testid="failed-logins-unlock"]').text(), 'the count can be cleared')
      .toBe('Clear failed logins')
  })

  it('unlocks a locked user and reads the panel again', async () => {
    getFailedLoginsMock.mockResolvedValueOnce(LOCKED).mockResolvedValue(CLEAN)
    const w = await mountView()

    await panel(w).find('[data-testid="failed-logins-unlock"]').trigger('click')
    await flushPromises()

    expect(unlockUserMock, 'the user of the page').toHaveBeenCalledWith('alice@example.com')
    expect(getFailedLoginsMock, 'the panel is read again').toHaveBeenCalledTimes(2)
    expect(field(w, 'state'), 'not locked any more').toBe('Not locked')
    expect(panel(w).find('[data-testid="failed-logins-unlock"]').exists(), 'nothing left to unlock').toBe(false)
    expect(
      toasts().some(t => t.severity === 'success' && t.summary === 'User unlocked'),
      'the unlock is confirmed',
    ).toBe(true)
  })

  it('clears the failed logins of a user who is not locked', async () => {
    getFailedLoginsMock.mockResolvedValueOnce(COUNTED).mockResolvedValue(CLEAN)
    // The server answers "was not locked"; the count is cleared all the same.
    unlockUserMock.mockResolvedValue(false)
    const w = await mountView()

    await panel(w).find('[data-testid="failed-logins-unlock"]').trigger('click')
    await flushPromises()

    expect(unlockUserMock, 'the same call as an unlock').toHaveBeenCalledWith('alice@example.com')
    expect(field(w, 'count'), 'the count is zero').toBe('Failed logins: 0')
    expect(
      toasts().some(t => t.severity === 'success' && t.summary === 'Failed logins cleared'),
      'said as what it was',
    ).toBe(true)
  })

  it('shows the error of a failed unlock and keeps the lock on the page', async () => {
    getFailedLoginsMock.mockResolvedValue(LOCKED)
    unlockUserMock.mockRejectedValue({
      response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } },
    })
    const w = await mountView()

    await panel(w).find('[data-testid="failed-logins-unlock"]').trigger('click')
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'error' && t.summary === 'Failed to unlock user' && t.detail === 'Access denied'),
      'the server message is shown',
    ).toBe(true)
    expect(field(w, 'state'), 'still locked').toBe('Locked')
  })

  it('offers no unlock without the permission to enable users', async () => {
    signInAs(['read', 'update'])
    getFailedLoginsMock.mockResolvedValue(LOCKED)

    const w = await mountView()

    expect(field(w, 'state'), 'the lock is shown').toBe('Locked')
    expect(panel(w).find('[data-testid="failed-logins-unlock"]').exists(), 'the action is not').toBe(false)
  })

  it('still shows the user when the failed logins cannot be loaded', async () => {
    getFailedLoginsMock.mockRejectedValue({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })

    const w = await mountView()

    expect(panel(w).find('[data-testid="failed-logins-error"]').text(), 'the reason')
      .toContain('Internal server error')
    expect(panel(w).find('[data-testid="failed-logins-state"]').exists(), 'no state is guessed').toBe(false)
    expect(w.text(), 'the user is shown').toContain('Auth Provider')
    expect(pushMock, 'the page is not left').not.toHaveBeenCalled()
  })

  it('does not ask for failed logins of a user that does not exist', async () => {
    getUserMock.mockRejectedValue({ response: { status: 404, data: { error: 'NOT_FOUND' } } })

    await mountView('ghost')

    expect(pushMock, 'back to the list').toHaveBeenCalledWith('/admin/users')
    expect(getFailedLoginsMock, 'no second request').not.toHaveBeenCalled()
  })
})
