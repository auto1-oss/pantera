import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import UserListView from '../UserListView.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import type { FailedLogins, User } from '@/types'

const listUsersMock = vi.fn()
const listLockedUsersMock = vi.fn()
const unlockUserMock = vi.fn()
const unlockAllUsersMock = vi.fn()

vi.mock('@/api/users', () => ({
  listUsers: (...a: unknown[]) => listUsersMock(...a),
  deleteUser: vi.fn(),
  enableUser: vi.fn(),
  disableUser: vi.fn(),
  putUser: vi.fn(),
}))

vi.mock('@/api/failedLogins', () => ({
  listLockedUsers: (...a: unknown[]) => listLockedUsersMock(...a),
  unlockUser: (...a: unknown[]) => unlockUserMock(...a),
  unlockAllUsers: (...a: unknown[]) => unlockAllUsersMock(...a),
}))

vi.mock('@/api/roles', () => ({
  listRoles: () => Promise.resolve({ items: [{ name: 'readers' }], total: 1 }),
}))

vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

// The user is stored with capitals; the lock is counted in lower case.
const ALICE: User = { name: 'Alice@Example.com', email: 'alice@example.com', enabled: true }
const BOB: User = { name: 'bob', email: 'bob@example.com', enabled: true }
const CAROL: User = { name: 'carol', enabled: false }

function lock(name: string): FailedLogins {
  return {
    name, locked: true, failures: 3, locked_at: '2026-01-01T10:00:04.099Z',
    last_failure_at: '2026-01-01T10:00:04.099Z', client_ip: '192.0.2.7',
    user_agent: 'Apache-Maven/3.9.9', path: 'repository', credential: 'password',
  }
}

interface ListParams { page?: number; size?: number; q?: string }

/** Answers `listUsers` from one list of users, paged the way the API pages. */
function serveUsers(all: User[]) {
  listUsersMock.mockImplementation(async (params: ListParams = {}) => {
    const page = params.page ?? 0
    const size = params.size ?? 20
    const matching = params.q
      ? all.filter(u => u.name.toLowerCase().includes(params.q!.toLowerCase()))
      : all
    const items = matching.slice(page * size, (page + 1) * size)
    return { items, page, size, total: matching.length, hasMore: (page + 1) * size < matching.length }
  })
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
  wrapper = mount(UserListView, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      stubs: { 'router-link': true },
    },
  })
  await flushPromises()
  return wrapper
}

function rowOf(w: VueWrapper, name: string): DOMWrapper<Element> | undefined {
  return w.findAll('tbody tr').find(tr => tr.text().includes(name))
}

function shownNames(w: VueWrapper): string[] {
  return w.findAll('tbody tr td:first-child').map(td => td.text())
}

async function showLockedOnly(w: VueWrapper) {
  await w.find('#locked-only').trigger('change')
  await flushPromises()
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
  listUsersMock.mockReset()
  serveUsers([ALICE, BOB, CAROL])
  listLockedUsersMock.mockReset().mockResolvedValue([])
  unlockUserMock.mockReset().mockResolvedValue(true)
  unlockAllUsersMock.mockReset().mockResolvedValue([])
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('UserListView: locked users', () => {
  it('tags the rows whose name, in lower case, is locked', async () => {
    listLockedUsersMock.mockResolvedValue([lock('alice@example.com')])

    const w = await mountView()

    expect(rowOf(w, 'Alice@Example.com')!.find('[data-testid="locked-tag"]').text(), 'alice is locked')
      .toBe('Locked')
    expect(rowOf(w, 'bob')!.find('[data-testid="locked-tag"]').exists(), 'bob is not').toBe(false)
    expect(rowOf(w, 'carol')!.find('[data-testid="locked-tag"]').exists(), 'carol is not').toBe(false)
    expect(rowOf(w, 'Alice@Example.com')!.text(), 'the account status is still shown').toContain('Active')
  })

  it('shows no tag, count or unlock when nobody is locked', async () => {
    const w = await mountView()

    expect(w.find('[data-testid="locked-tag"]').exists(), 'no tag').toBe(false)
    expect(w.find('[data-testid="locked-count"]').exists(), 'no count').toBe(false)
    expect(w.find('[data-testid="unlock-all"]').exists(), 'no unlock all').toBe(false)
    expect(w.find('[data-testid="unlock-user"]').exists(), 'no unlock per row').toBe(false)
    expect(shownNames(w), 'the users are listed as before').toEqual(['Alice@Example.com', 'bob', 'carol'])
  })

  it('does not match a locked name that is only part of a user name', async () => {
    listLockedUsersMock.mockResolvedValue([lock('bo'), lock('carol@example.com')])

    const w = await mountView()

    expect(w.find('[data-testid="locked-tag"]').exists()).toBe(false)
  })

  it('"Locked only" shows the locked users and nobody else', async () => {
    listLockedUsersMock.mockResolvedValue([lock('alice@example.com'), lock('carol')])
    const w = await mountView()

    await showLockedOnly(w)

    expect(shownNames(w), 'only the locked users').toEqual(['Alice@Example.com', 'carol'])

    await showLockedOnly(w)

    expect(shownNames(w), 'everybody again without the filter').toEqual(['Alice@Example.com', 'bob', 'carol'])
  })

  it('"Locked only" finds a locked user beyond the first page of users', async () => {
    const many: User[] = Array.from({ length: 250 }, (_, i) => ({ name: `user-${String(i).padStart(3, '0')}` }))
    serveUsers(many)
    listLockedUsersMock.mockResolvedValue([lock('user-007'), lock('user-240')])
    const w = await mountView()
    expect(shownNames(w), 'the first page holds one of them').toContain('user-007')
    expect(shownNames(w), 'and not the other').not.toContain('user-240')

    await showLockedOnly(w)

    expect(shownNames(w), 'both are shown').toEqual(['user-007', 'user-240'])
    const scans = listUsersMock.mock.calls.map(c => c[0] as ListParams).filter(p => p.size === 100)
    expect(scans.map(p => p.page), 'every page of users is read once').toEqual([0, 1, 2])
  })

  it('"Locked only" loads the locked names again before it filters', async () => {
    const w = await mountView()
    expect(shownNames(w), 'nobody is locked at first').toHaveLength(3)
    listLockedUsersMock.mockResolvedValue([lock('bob')])

    await showLockedOnly(w)

    expect(listLockedUsersMock, 'the list is requested again').toHaveBeenCalledTimes(2)
    expect(shownNames(w), 'the lock of this moment').toEqual(['bob'])
  })

  it('"Locked only" searches among the locked users', async () => {
    listLockedUsersMock.mockResolvedValue([lock('alice@example.com'), lock('carol')])
    const w = await mountView()
    await showLockedOnly(w)

    const search = w.find('input[placeholder="Search users..."]')
    await search.setValue('car')
    await search.trigger('keyup.enter')
    await flushPromises()

    expect(shownNames(w)).toEqual(['carol'])
  })

  it('"Locked only" says that nobody is locked', async () => {
    const w = await mountView()

    await showLockedOnly(w)

    expect(w.find('[data-testid="locked-only-empty"]').text(), 'the empty state').toBe('No user is locked')
    expect(
      listUsersMock.mock.calls.some(c => (c[0] as ListParams).size === 100),
      'no user page is read when no name is locked',
    ).toBe(false)
  })

  it('"Locked only" points to System Settings for a locked name that is no Pantera user', async () => {
    listLockedUsersMock.mockResolvedValue([lock('no-such-user')])
    const w = await mountView()
    expect(w.find('[data-testid="locked-tag"]').exists(), 'no row is tagged').toBe(false)
    expect(w.find('[data-testid="locked-count"]').text(), 'the name is counted').toBe('1 locked')

    await showLockedOnly(w)

    const empty = w.find('[data-testid="locked-only-empty"]').text()
    expect(empty, 'no Pantera user is locked').toContain('No Pantera user among the 1 locked name')
    expect(empty, 'where the name can be seen').toContain('System Settings → Failed logins')
  })

  it('"Locked only" stops reading after 2000 users and says so', async () => {
    const many: User[] = Array.from({ length: 2100 }, (_, i) => ({ name: `user-${String(i).padStart(4, '0')}` }))
    serveUsers(many)
    listLockedUsersMock.mockResolvedValue([lock('user-0003'), lock('user-2050')])
    const w = await mountView()

    await showLockedOnly(w)

    const scans = listUsersMock.mock.calls.map(c => c[0] as ListParams).filter(p => p.size === 100)
    expect(scans.length, 'twenty pages of a hundred').toBe(20)
    expect(shownNames(w), 'the locked user among those read').toEqual(['user-0003'])
    expect(w.find('[data-testid="locked-scan-cut"]').text(), 'the limit is named')
      .toContain('Only the first 2000 users were checked')
  })

  it('unlocks the user of a row and loads the locked list again', async () => {
    listLockedUsersMock.mockResolvedValueOnce([lock('alice@example.com')]).mockResolvedValue([])
    const w = await mountView()

    await rowOf(w, 'Alice@Example.com')!.find('[data-testid="unlock-user"]').trigger('click')
    await flushPromises()

    expect(unlockUserMock, 'the user of the row').toHaveBeenCalledWith('Alice@Example.com')
    expect(listLockedUsersMock, 'the locked list is loaded again').toHaveBeenCalledTimes(2)
    expect(w.find('[data-testid="locked-tag"]').exists(), 'the tag is gone').toBe(false)
    expect(w.find('[data-testid="unlock-user"]').exists(), 'and the action with it').toBe(false)
    expect(
      toasts().some(t => t.severity === 'success' && t.summary === 'User unlocked'),
      'the unlock is confirmed',
    ).toBe(true)
  })

  it('offers the unlock only on locked rows', async () => {
    listLockedUsersMock.mockResolvedValue([lock('alice@example.com')])

    const w = await mountView()

    expect(rowOf(w, 'Alice@Example.com')!.find('[data-testid="unlock-user"]').exists(), 'on the locked row')
      .toBe(true)
    expect(rowOf(w, 'bob')!.find('[data-testid="unlock-user"]').exists(), 'not on another row').toBe(false)
  })

  it('removes an unlocked user from the "Locked only" rows', async () => {
    listLockedUsersMock.mockResolvedValue([lock('alice@example.com'), lock('carol')])
    const w = await mountView()
    await showLockedOnly(w)
    listLockedUsersMock.mockResolvedValue([lock('carol')])

    await rowOf(w, 'Alice@Example.com')!.find('[data-testid="unlock-user"]').trigger('click')
    await flushPromises()

    expect(shownNames(w)).toEqual(['carol'])
  })

  it('shows the error of a failed unlock and keeps the tag', async () => {
    listLockedUsersMock.mockResolvedValue([lock('bob')])
    unlockUserMock.mockRejectedValue({
      response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } },
    })
    const w = await mountView()

    await rowOf(w, 'bob')!.find('[data-testid="unlock-user"]').trigger('click')
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'error' && t.summary === 'Failed to unlock user' && t.detail === 'Access denied'),
      'the server message is shown',
    ).toBe(true)
    expect(rowOf(w, 'bob')!.find('[data-testid="locked-tag"]').exists(), 'the tag stays').toBe(true)
  })

  it('unlocks all after a confirmation', async () => {
    listLockedUsersMock
      .mockResolvedValueOnce([lock('alice@example.com'), lock('no-such-user')])
      .mockResolvedValue([])
    unlockAllUsersMock.mockResolvedValue(['alice@example.com', 'no-such-user'])
    const w = await mountView()

    await w.find('[data-testid="unlock-all"]').trigger('click')
    await flushPromises()

    expect(unlockAllUsersMock, 'nothing happens before the confirmation').not.toHaveBeenCalled()
    expect(inBody('unlock-all-dialog')?.textContent, 'the dialog names the number').toContain('Unlock 2 locked names?')

    inBody<HTMLButtonElement>('unlock-all-confirm')!.click()
    await flushPromises()

    expect(unlockAllUsersMock, 'one request').toHaveBeenCalledTimes(1)
    expect(listLockedUsersMock, 'the locked list is loaded again').toHaveBeenCalledTimes(2)
    expect(w.find('[data-testid="locked-tag"]').exists(), 'no tag is left').toBe(false)
    expect(w.find('[data-testid="unlock-all"]').exists(), 'nothing is left to unlock').toBe(false)
    expect(inBody('unlock-all-dialog'), 'the dialog is closed').toBeNull()
  })

  it('offers "Unlock all" when the only locked name is no Pantera user', async () => {
    listLockedUsersMock.mockResolvedValue([lock('no-such-user')])

    const w = await mountView()

    expect(w.find('[data-testid="unlock-all"]').exists()).toBe(true)
  })

  it('unlocks nobody when the confirmation is cancelled', async () => {
    listLockedUsersMock.mockResolvedValue([lock('bob')])
    const w = await mountView()

    await w.find('[data-testid="unlock-all"]').trigger('click')
    await flushPromises()
    inBody<HTMLButtonElement>('unlock-all-cancel')!.click()
    await flushPromises()

    expect(unlockAllUsersMock, 'no request').not.toHaveBeenCalled()
    expect(rowOf(w, 'bob')!.find('[data-testid="locked-tag"]').exists(), 'the tag stays').toBe(true)
  })

  it('hides the unlock actions without the permission to enable users', async () => {
    signInAs(['read', 'update', 'delete'])
    listLockedUsersMock.mockResolvedValue([lock('alice@example.com')])

    const w = await mountView()

    expect(rowOf(w, 'Alice@Example.com')!.find('[data-testid="locked-tag"]').exists(), 'the tag is shown')
      .toBe(true)
    expect(w.find('[data-testid="unlock-user"]').exists(), 'no unlock per row').toBe(false)
    expect(w.find('[data-testid="unlock-all"]').exists(), 'no unlock all').toBe(false)
  })

  it('says so when the locked users cannot be loaded and still lists the users', async () => {
    listLockedUsersMock.mockRejectedValue({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })

    const w = await mountView()

    expect(w.find('[data-testid="locked-load-error"]').text(), 'the reason')
      .toContain('Internal server error')
    expect(shownNames(w), 'the users are listed').toEqual(['Alice@Example.com', 'bob', 'carol'])
    expect(w.find('[data-testid="locked-tag"]').exists(), 'no tag is guessed').toBe(false)
  })
})
