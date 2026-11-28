import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import SignInFailedPasswords from '../SignInFailedPasswords.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { localDateTime } from '@/utils/relativeTime'
import type { FailedPasswordLogin, PasswordLoginState, SignInFailuresQuery } from '@/api/signInFailures'

const listFailedPasswordLoginsMock = vi.fn()
const unlockUserMock = vi.fn()
const listUsersMock = vi.fn()

vi.mock('@/api/signInFailures', () => ({
  listFailedPasswordLogins: (...a: unknown[]) => listFailedPasswordLoginsMock(...a),
}))

vi.mock('@/api/failedLogins', () => ({
  unlockUser: (...a: unknown[]) => unlockUserMock(...a),
}))

vi.mock('@/api/users', () => ({
  listUsers: (...a: unknown[]) => listUsersMock(...a),
}))

const NO_STATE: PasswordLoginState = {
  failures: 0, locked: false, locked_at: null, delayed_seconds: 0, unlocked_at: null, unlocked_by: null,
}

/** A locked user: every field of the line has a value of its own. */
const LOCKED: FailedPasswordLogin = {
  user: 'alice@example.com', client_ip: '192.0.2.7', user_agent: 'Apache-Maven/3.9.9', path: 'repository',
  failed: 3, refused: 12, last_reason: 'account_locked',
  first_failure_at: '2026-11-25T16:12:00Z', last_failure_at: '2026-11-25T19:29:00Z',
  state: {
    failures: 5, locked: true, locked_at: '2026-11-25T16:20:00Z', delayed_seconds: 0,
    unlocked_at: '2026-11-20T08:00:00Z', unlocked_by: 'root',
  },
}

/** A user whose logins are delayed, from the sign-in form. */
const DELAYED: FailedPasswordLogin = {
  user: 'bob', client_ip: '192.0.2.9', user_agent: 'Mozilla/5.0', path: 'ui',
  failed: 2, refused: 1, last_reason: 'login_delayed',
  first_failure_at: '2026-11-26T10:00:00Z', last_failure_at: '2026-11-26T10:00:05Z',
  state: { ...NO_STATE, failures: 2, delayed_seconds: 37 },
}

/** One failed login and nothing in force. */
const ONCE: FailedPasswordLogin = {
  user: 'carol', client_ip: '', user_agent: '', path: 'api',
  failed: 1, refused: 0, last_reason: 'wrong_credentials',
  first_failure_at: '2026-11-26T11:00:00Z', last_failure_at: '2026-11-26T11:00:00Z',
  state: { ...NO_STATE, failures: 1 },
}

function page(items: FailedPasswordLogin[], total = items.length) {
  return { items, page: 0, size: 50, total, hasMore: false }
}

function query(n = listFailedPasswordLoginsMock.mock.calls.length - 1): SignInFailuresQuery {
  return listFailedPasswordLoginsMock.mock.calls[n][0] as SignInFailuresQuery
}

/** Answers `listUsers` from the Pantera users there are, matched the way the API matches. */
function serveUsers(names: string[]) {
  listUsersMock.mockImplementation(async (params: { q?: string } = {}) => {
    const items = names
      .filter(name => name.toLowerCase().includes((params.q ?? '').toLowerCase()))
      .map(name => ({ name }))
    return { items, page: 0, size: 100, total: items.length, hasMore: false }
  })
}

function afterDebounce() {
  return new Promise(resolve => setTimeout(resolve, 350))
}

let wrapper: VueWrapper | null = null

function signInAs(actions: string[]) {
  const auth = useAuthStore()
  auth.user = {
    name: 'admin', context: 'local',
    permissions: { api_user_permissions: actions },
  } as unknown as typeof auth.user
}

async function mountTab() {
  wrapper = mount(SignInFailedPasswords, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      directives: { tooltip: {} },
      stubs: {
        'router-link': { props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

function rows(w: VueWrapper): DOMWrapper<Element>[] {
  return w.findAll('tbody tr')
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
  listFailedPasswordLoginsMock.mockReset().mockResolvedValue(page([LOCKED]))
  unlockUserMock.mockReset().mockResolvedValue(true)
  listUsersMock.mockReset()
  serveUsers([])
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('Failed password logins: the lines', () => {
  it('shows every field of a line', async () => {
    const w = await mountTab()
    const row = rows(w)[0]

    expect(row.find('[data-testid="line-user"]').text(), 'the user').toBe('alice@example.com')
    expect(row.find('[data-testid="pw-failed"]').text(), 'failed logins').toBe('3')
    expect(row.find('[data-testid="pw-refused"]').text(), 'refused logins').toBe('12')
    for (const part of [
      '192.0.2.7', 'Apache-Maven/3.9.9', 'repository',
      localDateTime(LOCKED.first_failure_at), localDateTime(LOCKED.last_failure_at),
    ]) {
      expect(row.text(), `the row shows "${part}"`).toContain(part)
    }
    for (const exact of ['2026-11-25T16:12:00Z', '2026-11-25T19:29:00Z']) {
      expect(row.find(`[title="${exact}"]`).exists(), `the exact value "${exact}" is a tooltip`).toBe(true)
    }
    for (const header of [
      'User', 'Failed', 'Refused', 'Last reason', 'First failure', 'Last failure',
      'Client address', 'Client program', 'Path', 'User now',
    ]) {
      expect(w.find('thead').text(), `column "${header}"`).toContain(header)
    }
  })

  it('says in the column heads what failed and refused count', async () => {
    const w = await mountTab()

    expect(w.find('[data-testid="pw-failed-head"]').attributes('title'), 'failed')
      .toBe('Logins that were checked and failed. They count towards the lock.')
    expect(w.find('[data-testid="pw-refused-head"]').attributes('title'), 'refused')
      .toBe('Logins refused without a check, during a delay or a lock.')
  })

  it.each([
    ['wrong_credentials', 'Wrong credentials'],
    ['login_delayed', 'Login delayed'],
    ['account_locked', 'Account locked'],
  ])('shows the last reason %s as "%s" with the token as tooltip', async (reason, text) => {
    listFailedPasswordLoginsMock.mockResolvedValue(page([{ ...ONCE, last_reason: reason }]))

    const w = await mountTab()
    const cell = rows(w)[0].find('[data-testid="pw-reason"]')

    expect(cell.text(), 'the text').toBe(text)
    expect(cell.attributes('title'), 'the token').toBe(reason)
  })

  it('shows "(no name)" for a line without a user and dashes for what is unknown', async () => {
    listFailedPasswordLoginsMock.mockResolvedValue(page([{ ...ONCE, user: '' }]))

    const w = await mountTab()
    const row = rows(w)[0]

    expect(row.find('[data-testid="line-user"]').text(), 'the user').toBe('(no name)')
    expect(row.text().match(/—/g)?.length, 'address and program').toBe(2)
  })

  it('says so when there is no failed password login', async () => {
    listFailedPasswordLoginsMock.mockResolvedValue(page([]))

    const w = await mountTab()

    expect(w.find('[data-testid="list-empty"]').text()).toBe('No failed password login')
  })
})

describe('Failed password logins: the state of the user now', () => {
  it('shows the failed logins on record, the lock and the last unlock', async () => {
    const w = await mountTab()
    const state = rows(w)[0].find('[data-testid="pw-state"]')

    expect(state.find('[data-testid="pw-on-record"]').text(), 'the count').toBe('5 failed logins on record')
    const locked = state.find('[data-testid="pw-locked"]')
    expect(locked.text(), 'the lock').toBe(`Locked since ${localDateTime('2026-11-25T16:20:00Z')}`)
    expect(locked.attributes('title'), 'the exact time of the lock').toBe('2026-11-25T16:20:00Z')
    const unlocked = state.find('[data-testid="pw-unlocked"]')
    expect(unlocked.text(), 'the last unlock')
      .toBe(`Unlocked by root on ${localDateTime('2026-11-20T08:00:00Z')}`)
    expect(unlocked.attributes('title'), 'the exact time of the unlock').toBe('2026-11-20T08:00:00Z')
    expect(state.find('[data-testid="pw-delayed"]').exists(), 'a locked user is not shown as delayed')
      .toBe(false)
  })

  it('shows for how long logins are still delayed', async () => {
    listFailedPasswordLoginsMock.mockResolvedValue(page([DELAYED]))

    const w = await mountTab()
    const state = rows(w)[0].find('[data-testid="pw-state"]')

    expect(state.find('[data-testid="pw-delayed"]').text(), 'the delay').toBe('Delayed for 37 s')
    expect(state.find('[data-testid="pw-locked"]').exists(), 'not locked').toBe(false)
    expect(state.find('[data-testid="pw-on-record"]').text(), 'the count').toBe('2 failed logins on record')
  })

  it('shows only the count for a user who is neither locked nor delayed', async () => {
    listFailedPasswordLoginsMock.mockResolvedValue(page([ONCE]))

    const w = await mountTab()
    const state = rows(w)[0].find('[data-testid="pw-state"]')

    expect(state.text()).toBe('1 failed login on record')
  })
})

describe('Failed password logins: unlock', () => {
  it('offers Unlock on the line of a locked user only', async () => {
    listFailedPasswordLoginsMock.mockResolvedValue(page([LOCKED, DELAYED, ONCE]))

    const w = await mountTab()

    expect(rows(w)[0].find('[data-testid="pw-unlock"]').exists(), 'on the locked user').toBe(true)
    expect(rows(w)[1].find('[data-testid="pw-unlock"]').exists(), 'not on the delayed user').toBe(false)
    expect(rows(w)[2].find('[data-testid="pw-unlock"]').exists(), 'not on the third').toBe(false)
  })

  it('offers no Unlock on a line without a user name', async () => {
    listFailedPasswordLoginsMock.mockResolvedValue(page([{ ...LOCKED, user: '' }]))

    const w = await mountTab()

    expect(rows(w)[0].find('[data-testid="pw-locked"]').exists(), 'the lock is shown').toBe(true)
    expect(w.find('[data-testid="pw-unlock"]').exists(), 'there is no name to unlock').toBe(false)
  })

  it('offers no Unlock without the permission to enable users', async () => {
    signInAs(['read', 'update'])

    const w = await mountTab()

    expect(rows(w).length, 'the line is still shown').toBe(1)
    expect(rows(w)[0].find('[data-testid="pw-locked"]').exists(), 'with its lock').toBe(true)
    expect(w.find('[data-testid="pw-unlock"]').exists(), 'no unlock').toBe(false)
  })

  it('unlocks after a confirmation and loads the list again', async () => {
    const w = await mountTab()
    listFailedPasswordLoginsMock.mockResolvedValue(page([{
      ...LOCKED, state: { ...LOCKED.state, failures: 0, locked: false, locked_at: null },
    }]))

    await rows(w)[0].find('[data-testid="pw-unlock"]').trigger('click')
    await flushPromises()

    expect(unlockUserMock, 'nothing happens before the confirmation').not.toHaveBeenCalled()
    expect(inBody('unlock-dialog')?.textContent, 'the dialog names the user').toContain('alice@example.com')

    inBody<HTMLButtonElement>('unlock-confirm')!.click()
    await flushPromises()

    expect(unlockUserMock, 'the user of the line').toHaveBeenCalledWith('alice@example.com')
    expect(unlockUserMock, 'one request').toHaveBeenCalledTimes(1)
    expect(listFailedPasswordLoginsMock, 'the list is loaded again').toHaveBeenCalledTimes(2)
    expect(query(), 'the same page and filters').toEqual({ page: 0, size: 50, user: '', client_ip: '' })
    expect(rows(w)[0].find('[data-testid="pw-locked"]').exists(), 'the lock is gone').toBe(false)
    expect(w.find('[data-testid="pw-unlock"]').exists(), 'and the action with it').toBe(false)
    expect(inBody('unlock-dialog'), 'the dialog is closed').toBeNull()
    expect(
      toasts().some(t => t.severity === 'success' && t.summary === 'User unlocked' && t.detail === 'alice@example.com'),
      'the unlock is confirmed',
    ).toBe(true)
  })

  it('unlocks nobody when the confirmation is cancelled', async () => {
    const w = await mountTab()

    await rows(w)[0].find('[data-testid="pw-unlock"]').trigger('click')
    await flushPromises()
    inBody<HTMLButtonElement>('unlock-cancel')!.click()
    await flushPromises()

    expect(unlockUserMock, 'no request').not.toHaveBeenCalled()
    expect(listFailedPasswordLoginsMock, 'no reload').toHaveBeenCalledTimes(1)
    expect(inBody('unlock-dialog'), 'the dialog is closed').toBeNull()
    expect(rows(w)[0].find('[data-testid="pw-locked"]').exists(), 'the lock stays').toBe(true)
  })

  it('shows the error of a failed unlock and keeps the dialog open', async () => {
    unlockUserMock.mockRejectedValue({
      response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } },
    })
    const w = await mountTab()

    await rows(w)[0].find('[data-testid="pw-unlock"]').trigger('click')
    await flushPromises()
    inBody<HTMLButtonElement>('unlock-confirm')!.click()
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'error' && t.summary === 'Failed to unlock user' && t.detail === 'Access denied'),
      'the server message is shown',
    ).toBe(true)
    expect(inBody('unlock-dialog'), 'the dialog stays open').not.toBeNull()
    expect(listFailedPasswordLoginsMock, 'no reload').toHaveBeenCalledTimes(1)
  })

  it('says so when the user was no longer locked', async () => {
    unlockUserMock.mockResolvedValue(false)
    const w = await mountTab()

    await rows(w)[0].find('[data-testid="pw-unlock"]').trigger('click')
    await flushPromises()
    inBody<HTMLButtonElement>('unlock-confirm')!.click()
    await flushPromises()

    expect(
      toasts().some(t => t.severity === 'info' && t.summary === 'User was not locked'),
      'somebody else unlocked first',
    ).toBe(true)
    expect(listFailedPasswordLoginsMock, 'the list is loaded again').toHaveBeenCalledTimes(2)
  })
})

describe('Failed password logins: the link to the user', () => {
  it('links a name that is a Pantera user to the page of that user', async () => {
    // The report counts names in lower case; the user is stored with capitals.
    serveUsers(['Alice@Example.com', 'malice@example.com.au'])

    const w = await mountTab()
    const link = rows(w)[0].find('a[data-to]')

    expect(link.text(), 'the name of the line is the link').toBe('alice@example.com')
    expect(JSON.parse(link.attributes('data-to')!), 'to the user as stored').toEqual({
      name: 'admin-user-detail', params: { name: 'Alice@Example.com' },
    })
    expect(listUsersMock.mock.calls[0][0], 'the name is looked up among the users')
      .toEqual({ q: 'alice@example.com', size: 100 })
  })

  it('does not link a name that is no Pantera user', async () => {
    serveUsers(['malice@example.com.au'])

    const w = await mountTab()

    expect(rows(w)[0].find('a[data-to]').exists(), 'no link').toBe(false)
    expect(rows(w)[0].find('[data-testid="line-user"]').text(), 'the name is shown').toBe('alice@example.com')
  })

  it('looks a name up once, however many lines and loads show it', async () => {
    serveUsers(['alice@example.com'])
    listFailedPasswordLoginsMock.mockResolvedValue(page([
      LOCKED, { ...LOCKED, client_ip: '192.0.2.8' }, { ...ONCE, user: '' },
    ]))
    const w = await mountTab()

    await w.find('[data-testid="list-refresh"]').trigger('click')
    await flushPromises()

    expect(listUsersMock, 'one lookup, and none for the line without a name').toHaveBeenCalledTimes(1)
    expect(w.findAll('a[data-to]').length, 'both lines of the user are linked').toBe(2)
  })

  it('neither looks up nor links without the permission to edit users', async () => {
    signInAs(['read', 'enable'])
    serveUsers(['alice@example.com'])

    const w = await mountTab()

    expect(listUsersMock, 'no lookup').not.toHaveBeenCalled()
    expect(rows(w)[0].find('a[data-to]').exists(), 'no link').toBe(false)
  })

  it('shows the lines without links when the lookup fails', async () => {
    listUsersMock.mockReset().mockRejectedValue(new Error('Network Error'))

    const w = await mountTab()

    expect(rows(w).length, 'the line is shown').toBe(1)
    expect(rows(w)[0].find('a[data-to]').exists(), 'without a link').toBe(false)
    expect(toasts().length, 'and without an error').toBe(0)
  })
})

describe('Failed password logins: filters, paging and refusal', () => {
  it('sends both filters to the server and starts at the first page', async () => {
    listFailedPasswordLoginsMock.mockResolvedValue(page([LOCKED], 120))
    const w = await mountTab()
    await w.find('.p-paginator-next').trigger('click')
    await flushPromises()
    expect(query().page, 'the second page is asked for').toBe(1)

    await w.find('[data-testid="list-filter-user"]').setValue('alice')
    await w.find('[data-testid="list-filter-client-ip"]').setValue('192.0.2')
    await afterDebounce()
    await flushPromises()

    expect(query()).toEqual({ page: 0, size: 50, user: 'alice', client_ip: '192.0.2' })
  })

  it('says that the permission is missing on a 403', async () => {
    listFailedPasswordLoginsMock.mockRejectedValue({
      response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } },
    })

    const w = await mountTab()

    expect(w.find('[data-testid="list-forbidden"]').text())
      .toBe('You do not have permission to see this list.')
    expect(w.find('table').exists(), 'no table').toBe(false)
  })

  it('shows the error of a failed load with a retry', async () => {
    listFailedPasswordLoginsMock.mockRejectedValueOnce({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })
    const w = await mountTab()
    expect(w.find('[data-testid="list-error"]').text(), 'the message').toContain('Internal server error')

    await w.find('[data-testid="list-retry"]').trigger('click')
    await flushPromises()

    expect(rows(w).length, 'the line is listed').toBe(1)
  })
})
