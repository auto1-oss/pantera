import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { createRouter, createMemoryHistory } from 'vue-router'
import SignInFailuresView from '../SignInFailuresView.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import type { FailedLogins } from '@/types'

const listRejectedApiKeysMock = vi.fn()
const listFailedPasswordLoginsMock = vi.fn()
const listSecondFactorLinesMock = vi.fn()
const listLockedUsersMock = vi.fn()
const unlockUserMock = vi.fn()
const unlockAllUsersMock = vi.fn()

vi.mock('@/api/signInFailures', () => ({
  listRejectedApiKeys: (...a: unknown[]) => listRejectedApiKeysMock(...a),
  listFailedPasswordLogins: (...a: unknown[]) => listFailedPasswordLoginsMock(...a),
  listSecondFactorLines: (...a: unknown[]) => listSecondFactorLinesMock(...a),
  getSignInReportSettings: () => Promise.resolve({
    sign_in_report_enabled: 'true', sign_in_report_retention_days: '90',
  }),
}))

vi.mock('@/api/failedLogins', () => ({
  listLockedUsers: (...a: unknown[]) => listLockedUsersMock(...a),
  unlockUser: (...a: unknown[]) => unlockUserMock(...a),
  unlockAllUsers: (...a: unknown[]) => unlockAllUsersMock(...a),
}))

vi.mock('@/api/users', () => ({
  listUsers: vi.fn().mockResolvedValue({ items: [], page: 0, size: 100, total: 0, hasMore: false }),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

const ALICE: FailedLogins = {
  name: 'alice@example.com', locked: true, failures: 3,
  locked_at: '2026-01-01T10:00:04.099Z', last_failure_at: '2026-01-01T10:00:04.099Z',
  client_ip: '192.0.2.7', user_agent: 'Apache-Maven/3.9.9', path: 'repository', credential: 'password',
}

/** A locked name that belongs to no Pantera user, with no origin recorded. */
const STRANGER: FailedLogins = {
  name: 'no-such-user', locked: true, failures: 7,
  locked_at: '2026-01-02T08:00:00Z', last_failure_at: null,
  client_ip: null, user_agent: null, path: null, credential: null,
}

/** The line of alice on the Failed password logins tab, while she is locked. */
const ALICE_LINE = {
  user: 'alice@example.com', client_ip: '192.0.2.7', user_agent: 'Apache-Maven/3.9.9', path: 'repository',
  failed: 3, refused: 0, last_reason: 'wrong_credentials',
  first_failure_at: '2026-01-01T10:00:00Z', last_failure_at: '2026-01-01T10:00:04.099Z',
  state: {
    failures: 3, locked: true, locked_at: '2026-01-01T10:00:04.099Z', delayed_seconds: 0,
    unlocked_at: null, unlocked_by: null,
  },
}

function page(items: unknown[]) {
  return { items, page: 0, size: 50, total: items.length, hasMore: false }
}

const ADMIN = ['read', 'create', 'update', 'delete', 'enable']

let wrapper: VueWrapper | null = null

async function mountPage(actions: string[] = ADMIN): Promise<VueWrapper> {
  const auth = useAuthStore()
  auth.user = {
    name: 'admin', context: 'local',
    permissions: { api_user_permissions: actions },
  } as unknown as typeof auth.user
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/sign-in-failures', component: { template: '<div />' } },
      { path: '/admin/users', component: { template: '<div />' } },
    ],
  })
  await router.push('/admin/sign-in-failures')
  await router.isReady()
  wrapper = mount(SignInFailuresView, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }], router],
      directives: { tooltip: {} },
      stubs: {
        'router-link': { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

function section(w: VueWrapper) {
  return w.find('[data-testid="sign-in-locked-users"]')
}

function inBody<T extends HTMLElement = HTMLElement>(testid: string): T | null {
  return document.querySelector(`[data-testid="${testid}"]`) as T | null
}

function toasts() {
  return useNotificationStore().toasts
}

beforeEach(() => {
  setActivePinia(createPinia())
  listRejectedApiKeysMock.mockReset().mockResolvedValue(page([]))
  listFailedPasswordLoginsMock.mockReset().mockResolvedValue(page([]))
  listSecondFactorLinesMock.mockReset().mockResolvedValue(page([]))
  listLockedUsersMock.mockReset().mockResolvedValue([])
  unlockUserMock.mockReset().mockResolvedValue(true)
  unlockAllUsersMock.mockReset().mockResolvedValue([])
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('SignInFailuresView: the locked users', () => {
  it('lists the locked users above the tabs', async () => {
    const w = await mountPage()

    expect(section(w).find('[data-testid="locked-users"]').exists(), 'the list is on the page').toBe(true)
    expect(
      section(w).element.compareDocumentPosition(w.find('[data-testid="sign-in-tabs"]').element)
        & Node.DOCUMENT_POSITION_FOLLOWING,
      'before the tabs',
    ).toBeTruthy()
  })

  it('says where a lock and the unlock come from', async () => {
    const w = await mountPage()

    expect(section(w).text()).toContain(
      'Names locked after failed logins, oldest lock first, with where their '
      + 'last failed login came from. A name need not belong to a Pantera user.',
    )
  })

  it('says that no user is locked', async () => {
    const w = await mountPage()

    expect(listLockedUsersMock, 'the list is requested').toHaveBeenCalledTimes(1)
    expect(section(w).find('[data-testid="locked-users-empty"]').text(), 'the empty state')
      .toBe('No user is locked')
    expect(section(w).find('[data-testid="locked-users-unlock-all"]').exists(), 'nothing to unlock').toBe(false)
  })

  it('lists each locked name with the origin of its last failed login', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE, STRANGER])

    const w = await mountPage()
    const rows = section(w).findAll('tbody tr')

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
      expect(section(w).find('thead').text(), `column "${header}"`).toContain(header)
    }
    expect(section(w).find('[data-testid="locked-users-count"]').text(), 'the number of locked names').toBe('(2)')
  })

  it('lists a locked name that is no Pantera user, with dashes for what is unknown', async () => {
    listLockedUsersMock.mockResolvedValue([STRANGER])

    const w = await mountPage()
    const row = section(w).find('tbody tr')

    expect(row.find('[data-testid="locked-user-name"]').text(), 'the name').toBe('no-such-user')
    expect(row.text(), 'the count').toContain('7')
    expect(row.text().match(/—/g)?.length, 'address, program, path and credential are unknown').toBe(4)
    expect(row.find('[data-testid="locked-user-unlock"]').exists(), 'it can be unlocked').toBe(true)
  })

  it('pages a long list', async () => {
    listLockedUsersMock.mockResolvedValue(
      Array.from({ length: 25 }, (_, i) => ({ ...STRANGER, name: `name-${String(i).padStart(2, '0')}` })),
    )

    const w = await mountPage()

    expect(section(w).find('[data-testid="locked-users-count"]').text(), 'every name is counted').toBe('(25)')
    expect(section(w).findAll('tbody tr').length, 'the first page').toBe(20)
    expect(section(w).find('.p-paginator').exists(), 'the rest is a page away').toBe(true)
  })

  it('shows no pager for a short list', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE, STRANGER])

    const w = await mountPage()

    expect(section(w).find('.p-paginator').exists()).toBe(false)
  })

  it('unlocks one name and loads the list again', async () => {
    listLockedUsersMock.mockResolvedValueOnce([ALICE, STRANGER]).mockResolvedValue([STRANGER])
    const w = await mountPage()

    await section(w).findAll('[data-testid="locked-user-unlock"]')[0].trigger('click')
    await flushPromises()

    expect(unlockUserMock, 'the name of the row, as the server gave it').toHaveBeenCalledWith('alice@example.com')
    expect(listLockedUsersMock, 'the list is loaded again').toHaveBeenCalledTimes(2)
    expect(section(w).findAll('tbody tr').length, 'the unlocked name is gone').toBe(1)
    expect(section(w).text(), 'the other name stays').toContain('no-such-user')
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
    const w = await mountPage()

    await section(w).find('[data-testid="locked-user-unlock"]').trigger('click')
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'error' && t.summary === 'Failed to unlock user' && t.detail === 'Access denied'),
      'the server message is shown',
    ).toBe(true)
    expect(section(w).findAll('tbody tr').length, 'the row stays').toBe(1)
  })

  it('unlocks all after a confirmation', async () => {
    listLockedUsersMock.mockResolvedValueOnce([ALICE, STRANGER]).mockResolvedValue([])
    unlockAllUsersMock.mockResolvedValue(['alice@example.com', 'no-such-user'])
    const w = await mountPage()

    await section(w).find('[data-testid="locked-users-unlock-all"]').trigger('click')
    await flushPromises()

    expect(unlockAllUsersMock, 'nothing happens before the confirmation').not.toHaveBeenCalled()
    expect(inBody('unlock-all-dialog')?.textContent, 'the dialog names the number').toContain('Unlock 2 locked names?')

    inBody<HTMLButtonElement>('unlock-all-confirm')!.click()
    await flushPromises()

    expect(unlockAllUsersMock, 'one request').toHaveBeenCalledTimes(1)
    expect(listLockedUsersMock, 'the list is loaded again').toHaveBeenCalledTimes(2)
    expect(section(w).find('[data-testid="locked-users-empty"]').exists(), 'nobody is locked any more').toBe(true)
    expect(inBody('unlock-all-dialog'), 'the dialog is closed').toBeNull()
    expect(
      toasts().some(t => t.severity === 'success' && t.summary === 'Unlocked 2 users'),
      'the unlock is confirmed',
    ).toBe(true)
  })

  it('unlocks nobody when the confirmation is cancelled', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE])
    const w = await mountPage()

    await section(w).find('[data-testid="locked-users-unlock-all"]').trigger('click')
    await flushPromises()
    inBody<HTMLButtonElement>('unlock-all-cancel')!.click()
    await flushPromises()

    expect(unlockAllUsersMock, 'no request').not.toHaveBeenCalled()
    expect(section(w).findAll('tbody tr').length, 'the row stays').toBe(1)
    expect(inBody('unlock-all-dialog'), 'the dialog is closed').toBeNull()
  })

  it('keeps the dialog open when unlocking all fails', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE])
    unlockAllUsersMock.mockRejectedValue({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })
    const w = await mountPage()

    await section(w).find('[data-testid="locked-users-unlock-all"]').trigger('click')
    await flushPromises()
    inBody<HTMLButtonElement>('unlock-all-confirm')!.click()
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'error' && t.summary === 'Failed to unlock all users'),
      'the failure is shown',
    ).toBe(true)
    expect(inBody('unlock-all-dialog'), 'the dialog stays open').not.toBeNull()
    expect(section(w).findAll('tbody tr').length, 'the row stays').toBe(1)
  })

  it('shows the list but offers no unlock to an account that may only read users', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE])

    const w = await mountPage(['read'])

    expect(section(w).findAll('tbody tr').length, 'the list is shown').toBe(1)
    expect(section(w).find('[data-testid="locked-user-unlock"]').exists(), 'no unlock per row').toBe(false)
    expect(section(w).find('[data-testid="locked-users-unlock-all"]').exists(), 'no unlock all').toBe(false)
  })

  it('says so when the list cannot be loaded and still shows the report', async () => {
    listLockedUsersMock.mockRejectedValue({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })

    const w = await mountPage()

    expect(section(w).find('[data-testid="locked-users-error"]').text(), 'the reason')
      .toContain('Internal server error')
    expect(section(w).find('[data-testid="locked-users-empty"]').exists(), 'not reported as "nobody locked"')
      .toBe(false)
    expect(listRejectedApiKeysMock, 'the first tab is loaded').toHaveBeenCalledTimes(1)
  })

  it('loads the list again on Refresh', async () => {
    listLockedUsersMock.mockResolvedValueOnce([]).mockResolvedValue([ALICE])
    const w = await mountPage()

    await section(w).find('[data-testid="locked-users-refresh"]').trigger('click')
    await flushPromises()

    expect(listLockedUsersMock, 'a second request').toHaveBeenCalledTimes(2)
    expect(section(w).findAll('tbody tr').length, 'the new lock is listed').toBe(1)
  })
})

describe('SignInFailuresView: an unlock reaches both lists', () => {
  it('reads the failed password logins again after an unlock in the locked users', async () => {
    listLockedUsersMock.mockResolvedValueOnce([ALICE]).mockResolvedValue([])
    listFailedPasswordLoginsMock.mockResolvedValue(page([ALICE_LINE]))
    const w = await mountPage()
    await w.find('[data-testid="sign-in-tab-passwords"]').trigger('click')
    await flushPromises()
    expect(listFailedPasswordLoginsMock, 'the tab is loaded once').toHaveBeenCalledTimes(1)

    await section(w).find('[data-testid="locked-user-unlock"]').trigger('click')
    await flushPromises()

    expect(listFailedPasswordLoginsMock, 'the state of the user is read again').toHaveBeenCalledTimes(2)
  })

  it('opens no tab that was not opened when a name is unlocked', async () => {
    listLockedUsersMock.mockResolvedValueOnce([ALICE]).mockResolvedValue([])
    const w = await mountPage()

    await section(w).find('[data-testid="locked-user-unlock"]').trigger('click')
    await flushPromises()

    expect(listFailedPasswordLoginsMock, 'the tab stays unloaded').not.toHaveBeenCalled()
  })

  it('reads the locked users again after an unlock on the failed password logins', async () => {
    listLockedUsersMock.mockResolvedValueOnce([ALICE]).mockResolvedValue([])
    listFailedPasswordLoginsMock.mockResolvedValue(page([ALICE_LINE]))
    const w = await mountPage()
    await w.find('[data-testid="sign-in-tab-passwords"]').trigger('click')
    await flushPromises()

    await w.find('[data-testid="pw-unlock"]').trigger('click')
    await flushPromises()
    inBody<HTMLButtonElement>('unlock-confirm')!.click()
    await flushPromises()

    expect(unlockUserMock, 'the user of the line').toHaveBeenCalledWith('alice@example.com')
    expect(listLockedUsersMock, 'the locked users are read again').toHaveBeenCalledTimes(2)
    expect(section(w).find('[data-testid="locked-users-empty"]').exists(), 'alice is gone from them').toBe(true)
  })

  it('leaves the locked users as they are when the unlock on the tab fails', async () => {
    listLockedUsersMock.mockResolvedValue([ALICE])
    listFailedPasswordLoginsMock.mockResolvedValue(page([ALICE_LINE]))
    unlockUserMock.mockRejectedValue({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })
    const w = await mountPage()
    await w.find('[data-testid="sign-in-tab-passwords"]').trigger('click')
    await flushPromises()

    await w.find('[data-testid="pw-unlock"]').trigger('click')
    await flushPromises()
    inBody<HTMLButtonElement>('unlock-confirm')!.click()
    await flushPromises()

    expect(listLockedUsersMock, 'no second request').toHaveBeenCalledTimes(1)
  })
})
