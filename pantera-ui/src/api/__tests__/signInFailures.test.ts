import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub the axios client: the wrappers are thin, so what matters is the
// URL, the query and the body each one sends.
const get = vi.fn()
const put = vi.fn()

vi.mock('../client', () => ({
  getApiClient: () => ({ get, put }),
}))

import {
  listRejectedApiKeys, listFailedPasswordLogins, listFormLimitHits, listMyRejectedKeys,
  getSignInReportSettings, updateSignInReportSettings,
} from '../signInFailures'

const KEY = {
  user: 'alice', client_ip: '192.0.2.7', user_agent: 'Apache-Maven/3.9.9', path: 'repository',
  verified: true, key_id: 'a922f096-2062-4c1f-b619-df3052cf208e', key_name: 'CI token',
  key_created_at: '2026-01-01T00:00:00Z', key_expires_at: '2026-02-01T00:00:00Z',
  reason: 'api_key_expired', rejected: 48,
  first_rejected_at: '2026-11-25T16:12:00Z', last_rejected_at: '2026-11-25T19:29:00Z',
}

function page<T>(items: T[]) {
  return { items, page: 0, size: 50, total: items.length, hasMore: false }
}

beforeEach(() => {
  get.mockReset(); put.mockReset()
})

describe('the three lists of all users', () => {
  it.each([
    ['rejected API keys', listRejectedApiKeys, '/sign-in-failures/api-keys'],
    ['failed password logins', listFailedPasswordLogins, '/sign-in-failures/passwords'],
    ['the sign-in form limit', listFormLimitHits, '/sign-in-failures/form-limit'],
  ] as const)('reads %s with page, size and both filters', async (_name, list, url) => {
    const answer = page([KEY])
    get.mockResolvedValue({ data: answer })
    const signal = new AbortController().signal

    await expect(
      list({ page: 2, size: 20, user: 'ali', client_ip: '192.0.2' }, signal),
    ).resolves.toEqual(answer)

    expect(get).toHaveBeenCalledWith(url, {
      params: { page: 2, size: 20, user: 'ali', client_ip: '192.0.2' },
      signal,
    })
  })

  it('sends no filter that is empty or only spaces', async () => {
    get.mockResolvedValue({ data: page([]) })

    await listRejectedApiKeys({ page: 0, size: 50, user: '  ', client_ip: '' })

    expect(get.mock.calls[0][1].params).toEqual({ page: 0, size: 50 })
  })

  it('sends a filter without the spaces around it', async () => {
    get.mockResolvedValue({ data: page([]) })

    await listFailedPasswordLogins({ user: ' alice ', client_ip: ' 192.0.2.7 ' })

    expect(get.mock.calls[0][1].params).toEqual({ user: 'alice', client_ip: '192.0.2.7' })
  })

  it('asks for the first page of the server when nothing is given', async () => {
    get.mockResolvedValue({ data: page([]) })

    await listFormLimitHits()

    expect(get).toHaveBeenCalledWith('/sign-in-failures/form-limit', { params: {}, signal: undefined })
  })

  it('passes a refusal on to the caller', async () => {
    const refused = { response: { status: 403, data: { error: 'FORBIDDEN', message: 'Access denied' } } }
    get.mockRejectedValue(refused)

    await expect(listRejectedApiKeys()).rejects.toBe(refused)
  })
})

describe('the rejected keys of the signed-in user', () => {
  it('reads /auth/rejected-keys and returns its lines', async () => {
    get.mockResolvedValue({ data: page([KEY]) })

    await expect(listMyRejectedKeys()).resolves.toEqual([KEY])

    expect(get).toHaveBeenCalledWith('/auth/rejected-keys', { params: {} })
  })

  it('takes a page size', async () => {
    get.mockResolvedValue({ data: page([]) })

    await listMyRejectedKeys({ size: 200 })

    expect(get).toHaveBeenCalledWith('/auth/rejected-keys', { params: { size: 200 } })
  })

  it('answers an empty list when the answer has no items', async () => {
    get.mockResolvedValue({ data: {} })

    await expect(listMyRejectedKeys()).resolves.toEqual([])
  })
})

describe('sign-in report settings', () => {
  it('reads both values from /admin/sign-in-report-settings', async () => {
    const settings = { sign_in_report_enabled: 'true', sign_in_report_retention_days: '90' }
    get.mockResolvedValue({ data: settings })

    await expect(getSignInReportSettings()).resolves.toEqual(settings)

    expect(get).toHaveBeenCalledWith('/admin/sign-in-report-settings')
  })

  it('sends the given keys unchanged to the same endpoint', async () => {
    put.mockResolvedValue({ status: 204, data: '' })

    await updateSignInReportSettings({ sign_in_report_retention_days: '30' })

    expect(put).toHaveBeenCalledWith(
      '/admin/sign-in-report-settings', { sign_in_report_retention_days: '30' },
    )
  })
})
