import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub the axios client: the wrappers are thin, so what matters is the
// URL and body each one sends and what it makes of the answer.
const get = vi.fn()
const put = vi.fn()
const post = vi.fn()

vi.mock('../client', () => ({
  getApiClient: () => ({ get, put, post }),
}))

import {
  getFailedLoginsSettings, updateFailedLoginsSettings,
  listLockedUsers, getFailedLogins, unlockUser, unlockAllUsers,
} from '../failedLogins'

const SETTINGS = {
  failed_logins_delay_from_failure: '2',
  failed_logins_first_delay_seconds: '1',
  failed_logins_max_delay_seconds: '60',
  failed_logins_lock_after: '3',
  failed_logins_unlock_after_minutes: '0',
  failed_logins_forget_after_minutes: '0',
  failed_logins_count_rejected_keys: 'false',
  failed_logins_lock_all_credentials: 'true',
  failed_logins_second_factor_limit: '5',
  failed_logins_second_factor_pause_minutes: '15',
}

const LOCKED = {
  name: 'alice@example.com', locked: true, failures: 3,
  locked_at: '2026-01-01T10:00:04.099Z', last_failure_at: '2026-01-01T10:00:04.099Z',
  client_ip: '192.0.2.7', user_agent: 'Apache-Maven/3.9.9', path: 'repository', credential: 'password',
}

beforeEach(() => {
  get.mockReset(); put.mockReset(); post.mockReset()
})

describe('failed-logins settings', () => {
  it('reads the policy from /admin/failed-logins-settings', async () => {
    get.mockResolvedValue({ data: SETTINGS })
    await expect(getFailedLoginsSettings()).resolves.toEqual(SETTINGS)
    expect(get).toHaveBeenCalledWith('/admin/failed-logins-settings')
  })

  it('sends the given keys unchanged to the same endpoint', async () => {
    put.mockResolvedValue({ status: 204, data: '' })
    await updateFailedLoginsSettings({ failed_logins_lock_after: '0' })
    expect(put).toHaveBeenCalledWith('/admin/failed-logins-settings', { failed_logins_lock_after: '0' })
  })

  it('sends the pause of an unconfirmed second factor to the same endpoint', async () => {
    put.mockResolvedValue({ status: 204, data: '' })
    const pause = {
      failed_logins_second_factor_limit: '3',
      failed_logins_second_factor_pause_minutes: '60',
    }
    await updateFailedLoginsSettings(pause)
    expect(put).toHaveBeenCalledWith('/admin/failed-logins-settings', pause)
  })
})

describe('locked users', () => {
  it('lists the locked names of /locked-users', async () => {
    get.mockResolvedValue({ data: { items: [LOCKED], total: 1 } })
    await expect(listLockedUsers()).resolves.toEqual([LOCKED])
    expect(get).toHaveBeenCalledWith('/locked-users')
  })

  it('answers an empty list when nobody is locked', async () => {
    get.mockResolvedValue({ data: { items: [], total: 0 } })
    await expect(listLockedUsers()).resolves.toEqual([])
  })

  it('answers an empty list when the answer has no items', async () => {
    get.mockResolvedValue({ data: {} })
    await expect(listLockedUsers()).resolves.toEqual([])
  })

  it('reads the failed logins of one name', async () => {
    get.mockResolvedValue({ data: { name: 'bob', failures: 0, locked: false } })
    await expect(getFailedLogins('bob')).resolves.toEqual({ name: 'bob', failures: 0, locked: false })
    expect(get).toHaveBeenCalledWith('/users/bob/failed-logins')
  })

  it('encodes a name with @ in the path', async () => {
    get.mockResolvedValue({ data: LOCKED })
    post.mockResolvedValue({ data: { name: 'alice@example.com', unlocked: true } })
    await getFailedLogins('alice@example.com')
    await unlockUser('alice@example.com')
    expect(get).toHaveBeenCalledWith('/users/alice%40example.com/failed-logins')
    expect(post).toHaveBeenCalledWith('/users/alice%40example.com/unlock')
  })

  it('encodes characters that would change the path', async () => {
    post.mockResolvedValue({ data: { unlocked: false } })
    await unlockUser('corp/alice smith?x#y')
    expect(post).toHaveBeenCalledWith('/users/corp%2Falice%20smith%3Fx%23y/unlock')
  })

  it('reports whether the unlocked name was locked', async () => {
    post.mockResolvedValueOnce({ data: { name: 'bob', unlocked: true } })
    await expect(unlockUser('bob')).resolves.toBe(true)
    post.mockResolvedValueOnce({ data: { name: 'bob', unlocked: false } })
    await expect(unlockUser('bob')).resolves.toBe(false)
    post.mockResolvedValueOnce({ data: {} })
    await expect(unlockUser('bob')).resolves.toBe(false)
  })

  it('unlocks everyone through /users/unlock-all and returns the names', async () => {
    post.mockResolvedValue({ data: { unlocked: ['a', 'b'], count: 2 } })
    await expect(unlockAllUsers()).resolves.toEqual(['a', 'b'])
    expect(post).toHaveBeenCalledWith('/users/unlock-all')
  })

  it('returns no names when nobody was locked', async () => {
    post.mockResolvedValue({ data: { unlocked: [], count: 0 } })
    await expect(unlockAllUsers()).resolves.toEqual([])
    post.mockResolvedValue({ data: {} })
    await expect(unlockAllUsers()).resolves.toEqual([])
  })

  it('passes a failed request on to the caller', async () => {
    const refused = { response: { status: 403, data: { error: 'FORBIDDEN', message: 'no' } } }
    post.mockRejectedValue(refused)
    await expect(unlockUser('bob')).rejects.toBe(refused)
  })
})
