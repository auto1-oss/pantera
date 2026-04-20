import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub the axios client so we can observe which URL + params each
// wrapper passes through. We only care about the request shape, not
// the network; the wrappers are thin enough that a contract-style
// unit test is the appropriate level.
const get = vi.fn()
const put = vi.fn()

vi.mock('../client', () => ({
  getApiClient: () => ({ get, put }),
}))

import {
  getCooldownBlocked,
  getCooldownHistory,
  getCooldownConfig,
  updateCooldownConfig,
} from '../settings'

describe('cooldown API wrappers', () => {
  beforeEach(() => {
    get.mockReset()
    put.mockReset()
    get.mockResolvedValue({ data: { items: [], page: 1, size: 25, total: 0, hasMore: false } })
    put.mockResolvedValue({ data: {} })
  })

  it('getCooldownBlocked forwards repo + repo_type filters to /cooldown/blocked', async () => {
    await getCooldownBlocked({
      repo: 'npm-proxy',
      repo_type: 'npm-proxy',
      page: 2,
      size: 50,
      search: 'lodash',
      sort_by: 'blocked_until',
      sort_dir: 'desc',
    })
    expect(get).toHaveBeenCalledTimes(1)
    const [url, config] = get.mock.calls[0]
    expect(url).toBe('/cooldown/blocked')
    expect(config.params).toEqual({
      repo: 'npm-proxy',
      repo_type: 'npm-proxy',
      page: 2,
      size: 50,
      search: 'lodash',
      sort_by: 'blocked_until',
      sort_dir: 'desc',
    })
  })

  it('getCooldownBlocked tolerates no args (default empty params)', async () => {
    await getCooldownBlocked()
    expect(get).toHaveBeenCalledWith('/cooldown/blocked', expect.objectContaining({ params: {} }))
  })

  it('getCooldownBlocked forwards AbortSignal', async () => {
    const ctrl = new AbortController()
    await getCooldownBlocked({}, ctrl.signal)
    const [, config] = get.mock.calls[0]
    expect(config.signal).toBe(ctrl.signal)
  })

  it('getCooldownHistory hits /cooldown/history with the same filter shape', async () => {
    await getCooldownHistory({
      repo: 'pypi-proxy',
      repo_type: 'pypi-proxy',
      page: 1,
      size: 25,
      sort_by: 'archived_at',
      sort_dir: 'asc',
    })
    const [url, config] = get.mock.calls[0]
    expect(url).toBe('/cooldown/history')
    expect(config.params.repo).toBe('pypi-proxy')
    expect(config.params.repo_type).toBe('pypi-proxy')
    expect(config.params.sort_by).toBe('archived_at')
    expect(config.params.sort_dir).toBe('asc')
  })

  it('getCooldownConfig GETs /cooldown/config and returns the payload', async () => {
    get.mockResolvedValueOnce({
      data: {
        enabled: true,
        minimum_allowed_age: '7d',
        history_retention_days: 90,
        cleanup_batch_limit: 500,
      },
    })
    const cfg = await getCooldownConfig()
    expect(get).toHaveBeenCalledWith('/cooldown/config')
    expect(cfg.enabled).toBe(true)
    expect(cfg.history_retention_days).toBe(90)
    expect(cfg.cleanup_batch_limit).toBe(500)
  })

  it('updateCooldownConfig PUTs the payload including new tunables', async () => {
    await updateCooldownConfig({
      enabled: true,
      minimum_allowed_age: '7d',
      history_retention_days: 30,
      cleanup_batch_limit: 1000,
    })
    expect(put).toHaveBeenCalledWith('/cooldown/config', {
      enabled: true,
      minimum_allowed_age: '7d',
      history_retention_days: 30,
      cleanup_batch_limit: 1000,
    })
  })
})
