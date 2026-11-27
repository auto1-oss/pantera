import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import SignInSecondFactor from '../SignInSecondFactor.vue'
import { localDateTime } from '@/utils/relativeTime'
import type { SecondFactorLine, SignInFailuresQuery } from '@/api/signInFailures'

const listSecondFactorLinesMock = vi.fn()

vi.mock('@/api/signInFailures', () => ({
  listSecondFactorLines: (...a: unknown[]) => listSecondFactorLinesMock(...a),
}))

/** A pause that is still on: every field of the line has a value of its own. */
const PAUSED: SecondFactorLine = {
  user: 'alice', client_ip: '192.0.2.9', user_agent: 'Mozilla/5.0', path: 'ui',
  unconfirmed: 3, refused: 2, last_reason: 'sign_in_paused', counted: 5,
  paused_until: '2026-11-27T10:15:00Z', paused: true,
  first_seen_at: '2026-11-27T09:58:00Z', last_seen_at: '2026-11-27T10:01:30Z',
}

/** A pause that has ended. */
const ENDED: SecondFactorLine = {
  ...PAUSED, user: 'bob', unconfirmed: 6, refused: 0, last_reason: 'second_factor_failed',
  paused: false, paused_until: '2026-11-20T08:15:00Z',
  first_seen_at: '2026-11-20T08:00:00Z', last_seen_at: '2026-11-20T08:00:40Z',
}

/** Unconfirmed sign-ins that reached no pause. */
const NO_PAUSE: SecondFactorLine = {
  ...PAUSED, user: 'carol', unconfirmed: 1, refused: 0, last_reason: 'second_factor_failed',
  counted: 1, paused: false, paused_until: null,
}

function page(items: SecondFactorLine[], total = items.length) {
  return { items, page: 0, size: 50, total, hasMore: false }
}

function query(n = listSecondFactorLinesMock.mock.calls.length - 1): SignInFailuresQuery {
  return listSecondFactorLinesMock.mock.calls[n][0] as SignInFailuresQuery
}

function afterDebounce() {
  return new Promise(resolve => setTimeout(resolve, 350))
}

let wrapper: VueWrapper | null = null

async function mountTab() {
  wrapper = mount(SignInSecondFactor, {
    attachTo: document.body,
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      directives: { tooltip: {} },
    },
  })
  await flushPromises()
  return wrapper
}

function rows(w: VueWrapper): DOMWrapper<Element>[] {
  return w.findAll('tbody tr')
}

beforeEach(() => {
  setActivePinia(createPinia())
  listSecondFactorLinesMock.mockReset().mockResolvedValue(page([PAUSED]))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('Sign-ins with an unconfirmed second factor', () => {
  it('shows every field of a line', async () => {
    const w = await mountTab()
    const row = rows(w)[0]

    expect(row.find('[data-testid="line-user"]').text(), 'the user').toBe('alice')
    expect(row.find('[data-testid="sf-unconfirmed"]').text(), 'unconfirmed sign-ins').toBe('3')
    expect(row.find('[data-testid="sf-refused"]').text(), 'refused sign-ins').toBe('2')
    expect(row.find('[data-testid="sf-counted"]').text(), 'counted towards the pause').toBe('5')
    for (const part of [
      '192.0.2.9', 'Mozilla/5.0', 'ui',
      localDateTime(PAUSED.first_seen_at), localDateTime(PAUSED.last_seen_at),
    ]) {
      expect(row.text(), `the row shows "${part}"`).toContain(part)
    }
    for (const exact of ['2026-11-27T10:15:00Z', '2026-11-27T09:58:00Z', '2026-11-27T10:01:30Z']) {
      expect(row.find(`[title="${exact}"]`).exists(), `the exact value "${exact}" is a tooltip`).toBe(true)
    }
    for (const header of [
      'User', 'Unconfirmed', 'Refused', 'Last reason', 'Counted', 'Paused until',
      'First seen', 'Last seen', 'Client address', 'Client program', 'Path',
    ]) {
      expect(w.find('thead').text(), `column "${header}"`).toContain(header)
    }
  })

  it.each([
    ['second_factor_failed', 'Second factor not confirmed'],
    ['sign_in_paused', 'Refused during a pause'],
  ] as const)('words the last reason %s as "%s", with the token as the tooltip', async (token, text) => {
    listSecondFactorLinesMock.mockResolvedValue(page([{ ...PAUSED, last_reason: token }]))

    const w = await mountTab()
    const reason = rows(w)[0].find('[data-testid="sf-reason"]')

    expect(reason.text(), 'in words').toBe(text)
    expect(reason.attributes('title'), 'the token').toBe(token)
  })

  it('says until when a pause that is still on lasts', async () => {
    const w = await mountTab()
    const pause = rows(w)[0].find('[data-testid="sf-pause"]')

    expect(pause.text(), 'the end of the pause').toContain(localDateTime('2026-11-27T10:15:00Z'))
    expect(pause.find('[data-testid="sf-paused"]').text(), 'still paused').toBe('Still paused')
    expect(pause.find('[data-testid="sf-pause-ended"]').exists(), 'not shown as ended').toBe(false)
  })

  it('says that a pause has ended, and when', async () => {
    listSecondFactorLinesMock.mockResolvedValue(page([ENDED]))

    const w = await mountTab()
    const pause = rows(w)[0].find('[data-testid="sf-pause"]')

    expect(pause.find('[data-testid="sf-pause-ended"]').text(), 'ended').toBe('Pause ended')
    expect(pause.find('[data-testid="sf-paused"]').exists(), 'no still-paused badge').toBe(false)
    expect(pause.text(), 'the end of the pause').toContain(localDateTime('2026-11-20T08:15:00Z'))
    expect(pause.find('[title="2026-11-20T08:15:00Z"]').exists(), 'with the exact time').toBe(true)
  })

  it('shows a dash for a line that reached no pause', async () => {
    listSecondFactorLinesMock.mockResolvedValue(page([NO_PAUSE]))

    const w = await mountTab()
    const pause = rows(w)[0].find('[data-testid="sf-pause"]')

    expect(pause.text(), 'no time').toBe('—')
    expect(pause.find('[data-testid="sf-paused"]').exists(), 'no badge').toBe(false)
    expect(pause.find('[data-testid="sf-pause-ended"]').exists(), 'nothing ended').toBe(false)
  })

  it('shows "(no name)" for a line without a user and dashes for what is unknown', async () => {
    listSecondFactorLinesMock.mockResolvedValue(page([{ ...PAUSED, user: '', client_ip: '', user_agent: '' }]))

    const w = await mountTab()
    const row = rows(w)[0]

    expect(row.find('[data-testid="line-user"]').text(), 'the user').toBe('(no name)')
    expect(row.text().match(/—/g)?.length, 'address and program').toBe(2)
  })

  it('says what the list holds and where the pause is set', async () => {
    const w = await mountTab()
    const intro = w.find('[data-testid="list-intro"]').text()

    expect(intro, 'what is listed').toContain('password was right but whose second factor was not confirmed')
    expect(intro, 'never the lock').toContain('never towards the lock')
    expect(intro, 'where it is set').toContain('System Settings → Failed logins')
  })

  it('sends both filters to the server and starts at the first page', async () => {
    listSecondFactorLinesMock.mockResolvedValue(page([PAUSED], 120))
    const w = await mountTab()
    expect(query(), 'the first page without a filter').toEqual({ page: 0, size: 50, user: '', client_ip: '' })
    await w.find('.p-paginator-next').trigger('click')
    await flushPromises()
    expect(query().page, 'the second page is asked for').toBe(1)

    await w.find('[data-testid="list-filter-user"]').setValue('alice')
    await w.find('[data-testid="list-filter-client-ip"]').setValue('192.0.2')
    await afterDebounce()
    await flushPromises()

    expect(query()).toEqual({ page: 0, size: 50, user: 'alice', client_ip: '192.0.2' })
  })

  it('says so when no sign-in had an unconfirmed second factor', async () => {
    listSecondFactorLinesMock.mockResolvedValue(page([]))

    const w = await mountTab()

    expect(w.find('[data-testid="list-empty"]').text()).toBe('No sign-in with an unconfirmed second factor')
  })

  it('says that the permission is missing on a 403', async () => {
    listSecondFactorLinesMock.mockRejectedValue({
      response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } },
    })

    const w = await mountTab()

    expect(w.find('[data-testid="list-forbidden"]').text())
      .toBe('You do not have permission to see this list.')
    expect(w.find('table').exists(), 'no table').toBe(false)
  })

  it('shows the error of a failed load with a retry', async () => {
    listSecondFactorLinesMock.mockRejectedValueOnce({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })
    const w = await mountTab()
    expect(w.find('[data-testid="list-error"]').text(), 'the message').toContain('Internal server error')

    await w.find('[data-testid="list-retry"]').trigger('click')
    await flushPromises()

    expect(rows(w).length, 'the line is listed').toBe(1)
  })
})
