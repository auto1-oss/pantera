import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import SignInRejectedKeys from '../SignInRejectedKeys.vue'
import { localDateTime } from '@/utils/relativeTime'
import type { RejectedApiKey, SignInFailuresQuery } from '@/api/signInFailures'

const listRejectedApiKeysMock = vi.fn()

vi.mock('@/api/signInFailures', () => ({
  listRejectedApiKeys: (...a: unknown[]) => listRejectedApiKeysMock(...a),
}))

const KEY_ID = 'a922f096-2062-4c1f-b619-df3052cf208e'

/** An expired key that is still stored: every field has a value. */
const EXPIRED: RejectedApiKey = {
  user: 'alice', client_ip: '192.0.2.7', user_agent: 'Apache-Maven/3.9.9', path: 'repository',
  verified: true, key_id: KEY_ID, key_name: 'CI token',
  key_created_at: '2026-01-01T00:00:00Z', key_expires_at: '2026-02-01T00:00:00Z',
  reason: 'api_key_expired', rejected: 48,
  first_rejected_at: '2026-11-25T16:12:00Z', last_rejected_at: '2026-11-25T19:29:00Z',
}

/** What this Pantera did not sign: no key id, and a name that proves nothing. */
const UNKNOWN: RejectedApiKey = {
  user: 'bob', client_ip: '', user_agent: '', path: 'api',
  verified: false, key_id: '', key_name: null, key_created_at: null, key_expires_at: null,
  reason: 'api_key_unknown', rejected: 1,
  first_rejected_at: '2026-11-26T10:00:00Z', last_rejected_at: '2026-11-26T10:00:00Z',
}

function page(items: RejectedApiKey[], total = items.length, pageNo = 0, size = 50) {
  return { items, page: pageNo, size, total, hasMore: (pageNo + 1) * size < total }
}

/** The query of the n-th request, the last one by default. */
function query(n = listRejectedApiKeysMock.mock.calls.length - 1): SignInFailuresQuery {
  return listRejectedApiKeysMock.mock.calls[n][0] as SignInFailuresQuery
}

/** Longer than the debounce of the filters. */
function afterDebounce() {
  return new Promise(resolve => setTimeout(resolve, 350))
}

let wrapper: VueWrapper | null = null

async function mountTab() {
  wrapper = mount(SignInRejectedKeys, {
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
  listRejectedApiKeysMock.mockReset().mockResolvedValue(page([EXPIRED]))
})

afterEach(() => {
  vi.useRealTimers()
  wrapper?.unmount()
  wrapper = null
  document.body.innerHTML = ''
})

describe('Rejected API keys: the lines', () => {
  it('asks for the first page without a filter', async () => {
    await mountTab()

    expect(listRejectedApiKeysMock, 'one request').toHaveBeenCalledTimes(1)
    expect(query(), 'page 0 at the size of the server, no filter').toEqual({
      page: 0, size: 50, user: '', client_ip: '',
    })
  })

  it('shows every field of a line', async () => {
    const w = await mountTab()
    const row = rows(w)[0]

    for (const part of [
      'alice', 'CI token', 'a922f096…', 'Expired', '48', '192.0.2.7', 'Apache-Maven/3.9.9', 'repository',
      localDateTime(EXPIRED.key_created_at), localDateTime(EXPIRED.key_expires_at),
      localDateTime(EXPIRED.first_rejected_at), localDateTime(EXPIRED.last_rejected_at),
    ]) {
      expect(row.text(), `the row shows "${part}"`).toContain(part)
    }
    expect(row.find('[data-testid="not-verified"]').exists(), 'a verified line carries no tag').toBe(false)
    for (const exact of [
      KEY_ID, '2026-01-01T00:00:00Z', '2026-02-01T00:00:00Z', '2026-11-25T16:12:00Z', '2026-11-25T19:29:00Z',
    ]) {
      expect(row.find(`[title="${exact}"]`).exists(), `the exact value "${exact}" is a tooltip`).toBe(true)
    }
    for (const header of [
      'User', 'Key', 'Key created', 'Key expires', 'Reason', 'Rejected', 'First rejected',
      'Last rejected', 'Client address', 'Client program', 'Path',
    ]) {
      expect(w.find('thead').text(), `column "${header}"`).toContain(header)
    }
  })

  it.each([
    ['api_key_expired', 'Expired'],
    ['api_key_revoked', 'Revoked'],
    ['api_key_refused', 'Not accepted'],
    ['api_key_unknown', 'Not issued here'],
  ])('shows the reason %s as "%s" with the token as tooltip', async (reason, text) => {
    listRejectedApiKeysMock.mockResolvedValue(page([{ ...EXPIRED, reason }]))

    const w = await mountTab()
    const cell = rows(w)[0].find('[data-testid="key-reason"]')

    expect(cell.text(), 'the text').toBe(text)
    expect(cell.attributes('title'), 'the token').toBe(reason)
  })

  it('tags a line whose key this Pantera did not issue', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([UNKNOWN, EXPIRED]))

    const w = await mountTab()
    const tag = rows(w)[0].find('[data-testid="not-verified"]')

    expect(tag.text(), 'the tag is visible').toBe('Not verified')
    expect(tag.attributes('title'), 'the tooltip says what it means').toBe(
      'This Pantera did not issue the key. The user name is only what the client sent.',
    )
    expect(rows(w)[0].text(), 'the name the client sent is still shown').toContain('bob')
    expect(rows(w)[1].find('[data-testid="not-verified"]').exists(), 'the verified line has no tag').toBe(false)
  })

  it('shows dashes for what an unknown key does not have', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([UNKNOWN]))

    const w = await mountTab()
    const row = rows(w)[0]

    expect(row.find('[data-testid="key-name"]').text(), 'no name and no id').toBe('—')
    expect(row.find('[data-testid="key-id"]').exists(), 'no id line').toBe(false)
    expect(row.text().match(/—/g)?.length, 'key, both key dates, address and program').toBe(5)
  })

  it('shows the shortened id of a key without a name', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([{
      ...EXPIRED, reason: 'api_key_revoked', key_name: null, key_created_at: null, key_expires_at: null,
    }]))

    const w = await mountTab()
    const name = rows(w)[0].find('[data-testid="key-name"]')

    expect(name.text(), 'the id, shortened').toBe('a922f096…')
    expect(name.attributes('title'), 'the whole id is the tooltip').toBe(KEY_ID)
    expect(rows(w)[0].find('[data-testid="key-id"]').exists(), 'the id is not shown twice').toBe(false)
  })

  it('says that a stored key without an end does not expire', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([{
      ...EXPIRED, reason: 'api_key_refused', key_expires_at: null,
    }]))

    const w = await mountTab()

    expect(rows(w)[0].find('[data-testid="key-expires"]').text()).toBe('No expiry')
  })

  it('shows "(no name)" for a line without a user', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([{ ...UNKNOWN, user: '' }]))

    const w = await mountTab()

    expect(rows(w)[0].find('[data-testid="line-user"]').text()).toBe('(no name)')
  })

  it('says what to do about the keys', async () => {
    const w = await mountTab()

    expect(w.find('[data-testid="list-intro"]').text())
      .toBe('A tool is still sending each of these keys. Replace the key in that tool.')
  })

  it('says how many lines there are', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([EXPIRED], 120))

    const w = await mountTab()

    expect(w.find('[data-testid="list-total"]').text()).toBe('120 lines')
  })
})

describe('Rejected API keys: filters', () => {
  it('sends the user filter to the server, once the typing stops', async () => {
    const w = await mountTab()
    const input = w.find('[data-testid="list-filter-user"]')
    vi.useFakeTimers()

    await input.setValue('a')
    vi.advanceTimersByTime(200)
    await input.setValue('al')
    vi.advanceTimersByTime(200)
    await input.setValue('ali')
    vi.advanceTimersByTime(299)
    expect(listRejectedApiKeysMock, 'nothing is sent while typing').toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(1)

    expect(listRejectedApiKeysMock, 'one request for the three keystrokes').toHaveBeenCalledTimes(2)
    expect(query(), 'the filter as typed').toEqual({ page: 0, size: 50, user: 'ali', client_ip: '' })
  })

  it('sends the client address filter to the server', async () => {
    const w = await mountTab()

    await w.find('[data-testid="list-filter-client-ip"]').setValue('192.0.2')
    await afterDebounce()
    await flushPromises()

    expect(query()).toEqual({ page: 0, size: 50, user: '', client_ip: '192.0.2' })
  })

  it('sends both filters together', async () => {
    const w = await mountTab()

    await w.find('[data-testid="list-filter-user"]').setValue('alice')
    await w.find('[data-testid="list-filter-client-ip"]').setValue('192.0.2.7')
    await afterDebounce()
    await flushPromises()

    expect(listRejectedApiKeysMock, 'one request for both').toHaveBeenCalledTimes(2)
    expect(query()).toEqual({ page: 0, size: 50, user: 'alice', client_ip: '192.0.2.7' })
  })

  it('sends a filter at once on Enter', async () => {
    const w = await mountTab()
    const input = w.find('[data-testid="list-filter-user"]')

    await input.setValue('alice')
    await input.trigger('keyup.enter')
    await flushPromises()

    expect(listRejectedApiKeysMock, 'sent without waiting').toHaveBeenCalledTimes(2)
    expect(query().user).toBe('alice')
    await afterDebounce()
    await flushPromises()
    expect(listRejectedApiKeysMock, 'and not a second time').toHaveBeenCalledTimes(2)
  })

  it('goes back to the first page when a filter changes', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([EXPIRED], 120))
    const w = await mountTab()
    await w.find('.p-paginator-next').trigger('click')
    await flushPromises()
    expect(query().page, 'the second page is shown').toBe(1)

    await w.find('[data-testid="list-filter-user"]').setValue('alice')
    await afterDebounce()
    await flushPromises()

    expect(query(), 'the filtered list starts at its first page').toEqual({
      page: 0, size: 50, user: 'alice', client_ip: '',
    })
  })

  it('does not let a slow answer replace the answer to a newer filter', async () => {
    const w = await mountTab()
    let answerSlow: (value: unknown) => void = () => {}
    listRejectedApiKeysMock
      .mockImplementationOnce(() => new Promise((resolve) => { answerSlow = resolve }))
      .mockResolvedValueOnce(page([{ ...EXPIRED, user: 'carol' }]))
    const input = w.find('[data-testid="list-filter-user"]')

    await input.setValue('a')
    await input.trigger('keyup.enter')
    await input.setValue('carol')
    await input.trigger('keyup.enter')
    await flushPromises()
    answerSlow(page([{ ...EXPIRED, user: 'anna' }]))
    await flushPromises()

    const first = listRejectedApiKeysMock.mock.calls[1][1] as AbortSignal
    expect(first.aborted, 'the request of the older filter is cancelled').toBe(true)
    expect(rows(w)[0].text(), 'the rows of the newer filter stay').toContain('carol')
    expect(w.text(), 'the late answer is dropped').not.toContain('anna')
  })
})

describe('Rejected API keys: paging', () => {
  it('asks the server for the page that is opened', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([EXPIRED], 120))
    const w = await mountTab()

    await w.find('.p-paginator-next').trigger('click')
    await flushPromises()
    expect(query(), 'the second page').toEqual({ page: 1, size: 50, user: '', client_ip: '' })

    await w.find('.p-paginator-next').trigger('click')
    await flushPromises()
    expect(query().page, 'the third page').toBe(2)

    await w.find('.p-paginator-prev').trigger('click')
    await flushPromises()
    expect(query().page, 'back to the second').toBe(1)
  })

  it('keeps the filter while paging', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([EXPIRED], 120))
    const w = await mountTab()
    const input = w.find('[data-testid="list-filter-user"]')
    await input.setValue('alice')
    await input.trigger('keyup.enter')
    await flushPromises()

    await w.find('.p-paginator-next').trigger('click')
    await flushPromises()

    expect(query()).toEqual({ page: 1, size: 50, user: 'alice', client_ip: '' })
  })

  it('shows no pager for a list that fits one page', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([EXPIRED, UNKNOWN]))

    const w = await mountTab()

    expect(w.find('.p-paginator').exists()).toBe(false)
  })

  it('steps back to the last page when the opened page has no lines any more', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([EXPIRED], 120))
    const w = await mountTab()
    listRejectedApiKeysMock
      .mockResolvedValueOnce(page([], 50, 1))
      .mockResolvedValue(page([EXPIRED], 50))

    await w.find('.p-paginator-next').trigger('click')
    await flushPromises()

    expect(query().page, 'the last page that has lines is asked for').toBe(0)
    expect(rows(w).length, 'and shown').toBe(1)
  })

  it('loads the same page again on Refresh', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([EXPIRED], 120))
    const w = await mountTab()
    await w.find('.p-paginator-next').trigger('click')
    await flushPromises()
    listRejectedApiKeysMock.mockResolvedValue(page([EXPIRED, UNKNOWN], 121, 1))

    await w.find('[data-testid="list-refresh"]').trigger('click')
    await flushPromises()

    expect(listRejectedApiKeysMock, 'a third request').toHaveBeenCalledTimes(3)
    expect(query(), 'the page that was open').toEqual({ page: 1, size: 50, user: '', client_ip: '' })
    expect(rows(w).length, 'the new line is listed').toBe(2)
  })
})

describe('Rejected API keys: states', () => {
  it('says that it is loading until the first answer', async () => {
    let answer: (value: unknown) => void = () => {}
    listRejectedApiKeysMock.mockImplementation(() => new Promise((resolve) => { answer = resolve }))

    const w = await mountTab()

    expect(w.find('[data-testid="list-loading"]').exists(), 'loading').toBe(true)
    expect(w.find('[data-testid="list-empty"]').exists(), 'not reported as empty').toBe(false)

    answer(page([EXPIRED]))
    await flushPromises()

    expect(w.find('[data-testid="list-loading"]').exists(), 'no longer loading').toBe(false)
    expect(rows(w).length, 'the line is listed').toBe(1)
  })

  it('says so when no key was rejected', async () => {
    listRejectedApiKeysMock.mockResolvedValue(page([]))

    const w = await mountTab()

    expect(w.find('[data-testid="list-empty"]').text(), 'the empty state').toBe('No API key was rejected')
    expect(w.find('table').exists(), 'no table').toBe(false)
  })

  it('says so when no line matches the filter', async () => {
    const w = await mountTab()
    listRejectedApiKeysMock.mockResolvedValue(page([]))
    const input = w.find('[data-testid="list-filter-user"]')

    await input.setValue('nobody')
    await input.trigger('keyup.enter')
    await flushPromises()

    expect(w.find('[data-testid="list-empty"]').text()).toBe('No line matches the filter')
  })

  it('says that the permission is missing on a 403, instead of the table', async () => {
    listRejectedApiKeysMock.mockRejectedValue({
      response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } },
    })

    const w = await mountTab()

    expect(w.find('[data-testid="list-forbidden"]').text(), 'the reason')
      .toBe('You do not have permission to see this list.')
    expect(w.find('table').exists(), 'no table').toBe(false)
    expect(w.find('[data-testid="list-error"]').exists(), 'not shown as a failure to retry').toBe(false)
    expect(w.find('[data-testid="list-empty"]').exists(), 'not reported as empty').toBe(false)
  })

  it('shows the error of a failed load and loads again on Retry', async () => {
    listRejectedApiKeysMock.mockRejectedValueOnce({
      response: { status: 500, data: { error: 'INTERNAL_ERROR', message: 'Internal server error' } },
    })
    const w = await mountTab()

    expect(w.find('[data-testid="list-error"]').text(), 'the message of the server')
      .toContain('Internal server error')
    expect(w.find('table').exists(), 'no table').toBe(false)
    expect(w.find('[data-testid="list-empty"]').exists(), 'not reported as empty').toBe(false)

    await w.find('[data-testid="list-retry"]').trigger('click')
    await flushPromises()

    expect(listRejectedApiKeysMock, 'a second request').toHaveBeenCalledTimes(2)
    expect(w.find('[data-testid="list-error"]').exists(), 'the error is gone').toBe(false)
    expect(rows(w).length, 'the line is listed').toBe(1)
  })

  it('shows no old lines beside the error of a failed reload', async () => {
    const w = await mountTab()
    listRejectedApiKeysMock.mockRejectedValue(new Error('Network Error'))

    await w.find('[data-testid="list-refresh"]').trigger('click')
    await flushPromises()

    expect(w.find('[data-testid="list-error"]').text(), 'the transport error').toContain('Network Error')
    expect(w.find('table').exists(), 'the lines of before are gone').toBe(false)
  })
})
