import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import SignInFormLimit from '../SignInFormLimit.vue'
import { localDateTime } from '@/utils/relativeTime'
import type { FormLimitHit, SignInFailuresQuery } from '@/api/signInFailures'

const listFormLimitHitsMock = vi.fn()

vi.mock('@/api/signInFailures', () => ({
  listFormLimitHits: (...a: unknown[]) => listFormLimitHitsMock(...a),
}))

/** A block that is still on: every field of the line has a value of its own. */
const BLOCKED: FormLimitHit = {
  user: 'alice', client_ip: '192.0.2.9', user_agent: 'Mozilla/5.0', path: 'ui',
  hits: 2, failures: 5, blocked_until: '2026-11-26T19:33:32Z', blocked: true,
  first_hit_at: '2026-11-26T19:18:32Z', last_hit_at: '2026-11-26T19:19:40Z',
}

const ENDED: FormLimitHit = {
  ...BLOCKED, user: 'bob', hits: 7, failures: 6, blocked: false,
  blocked_until: '2026-11-20T08:15:00Z',
  first_hit_at: '2026-11-20T08:00:00Z', last_hit_at: '2026-11-20T08:01:00Z',
}

function page(items: FormLimitHit[], total = items.length) {
  return { items, page: 0, size: 50, total, hasMore: false }
}

function query(n = listFormLimitHitsMock.mock.calls.length - 1): SignInFailuresQuery {
  return listFormLimitHitsMock.mock.calls[n][0] as SignInFailuresQuery
}

function afterDebounce() {
  return new Promise(resolve => setTimeout(resolve, 350))
}

let wrapper: VueWrapper | null = null

async function mountTab() {
  wrapper = mount(SignInFormLimit, {
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
  listFormLimitHitsMock.mockReset().mockResolvedValue(page([BLOCKED]))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('Sign-in form limit', () => {
  it('shows every field of a line', async () => {
    const w = await mountTab()
    const row = rows(w)[0]

    expect(row.find('[data-testid="line-user"]').text(), 'the user').toBe('alice')
    expect(row.find('[data-testid="limit-hits"]').text(), 'refused logins').toBe('2')
    expect(row.find('[data-testid="limit-failures"]').text(), 'failures in the window').toBe('5')
    for (const part of [
      '192.0.2.9', 'Mozilla/5.0', 'ui',
      localDateTime(BLOCKED.first_hit_at), localDateTime(BLOCKED.last_hit_at),
    ]) {
      expect(row.text(), `the row shows "${part}"`).toContain(part)
    }
    for (const exact of ['2026-11-26T19:33:32Z', '2026-11-26T19:18:32Z', '2026-11-26T19:19:40Z']) {
      expect(row.find(`[title="${exact}"]`).exists(), `the exact value "${exact}" is a tooltip`).toBe(true)
    }
    for (const header of [
      'User', 'Refused logins', 'Failures in the window', 'Block', 'First refused', 'Last refused',
      'Client address', 'Client program', 'Path',
    ]) {
      expect(w.find('thead').text(), `column "${header}"`).toContain(header)
    }
  })

  it('says until when a block that is still on lasts', async () => {
    const w = await mountTab()
    const block = rows(w)[0].find('[data-testid="limit-block"]')

    expect(block.text()).toBe(`Blocked until ${localDateTime('2026-11-26T19:33:32Z')}`)
  })

  it('says that a block has ended, and when', async () => {
    listFormLimitHitsMock.mockResolvedValue(page([ENDED]))

    const w = await mountTab()
    const block = rows(w)[0].find('[data-testid="limit-block"]')

    expect(block.text(), 'ended').toContain('Block ended')
    expect(block.text(), 'not shown as still on').not.toContain('Blocked until')
    expect(block.text(), 'the end of the block').toContain(localDateTime('2026-11-20T08:15:00Z'))
    expect(block.find('[title="2026-11-20T08:15:00Z"]').exists(), 'with the exact time').toBe(true)
  })

  it('shows "(no name)" for a line without a user and dashes for what is unknown', async () => {
    listFormLimitHitsMock.mockResolvedValue(page([{ ...BLOCKED, user: '', client_ip: '', user_agent: '' }]))

    const w = await mountTab()
    const row = rows(w)[0]

    expect(row.find('[data-testid="line-user"]').text(), 'the user').toBe('(no name)')
    expect(row.text().match(/—/g)?.length, 'address and program').toBe(2)
  })

  it('says which limit this is', async () => {
    const w = await mountTab()

    expect(w.find('[data-testid="list-intro"]').text()).toContain('Login Throttling')
  })

  it('sends both filters to the server and starts at the first page', async () => {
    listFormLimitHitsMock.mockResolvedValue(page([BLOCKED], 120))
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

  it('says so when the limit refused no login', async () => {
    listFormLimitHitsMock.mockResolvedValue(page([]))

    const w = await mountTab()

    expect(w.find('[data-testid="list-empty"]').text()).toBe('The sign-in form limit refused no login')
  })

  it('says that the permission is missing on a 403', async () => {
    listFormLimitHitsMock.mockRejectedValue({
      response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } },
    })

    const w = await mountTab()

    expect(w.find('[data-testid="list-forbidden"]').text())
      .toBe('You do not have permission to see this list.')
    expect(w.find('table').exists(), 'no table').toBe(false)
  })

  it('shows the error of a failed load with a retry', async () => {
    listFormLimitHitsMock.mockRejectedValueOnce({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })
    const w = await mountTab()
    expect(w.find('[data-testid="list-error"]').text(), 'the message').toContain('Internal server error')

    await w.find('[data-testid="list-retry"]').trigger('click')
    await flushPromises()

    expect(rows(w).length, 'the line is listed').toBe(1)
  })
})
