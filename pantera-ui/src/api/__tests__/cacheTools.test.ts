import { describe, it, expect, vi, beforeEach } from 'vitest'

const get = vi.fn()
const post = vi.fn()

vi.mock('../client', () => ({
  getApiClient: () => ({ get, post }),
}))

import { invalidateNegCacheKey, invalidateNegCachePackage, listNegCache, probeNegCacheUrl } from '../negCache'
import { inspectCooldownPackage, refreshCooldownPackage } from '../cooldown'
import { fixPath, runTroubleshootFix, troubleshootUrl } from '../troubleshoot'

describe('cache tools API wrappers', () => {
  beforeEach(() => {
    get.mockReset()
    post.mockReset()
  })

  it('listNegCache forwards q/scope/repoType/paging and defaults missing fields', async () => {
    get.mockResolvedValue({ data: { items: [], total: 0 } })
    const resp = await listNegCache({ q: 'lodash', scope: 'npm-group', repoType: 'npm', page: 1, pageSize: 50 })
    expect(get).toHaveBeenCalledWith('/admin/neg-cache', {
      params: { q: 'lodash', scope: 'npm-group', repoType: 'npm', page: 1, pageSize: 50 },
      signal: undefined,
    })
    expect(resp.source).toBe('L1-only')
  })

  it('probeNegCacheUrl sends the url parameter', async () => {
    get.mockResolvedValue({ data: { keys: [], shadowed: false } })
    await probeNegCacheUrl('/npm-group/lodash')
    expect(get).toHaveBeenCalledWith('/admin/neg-cache/probe', { params: { url: '/npm-group/lodash' } })
  })

  it('invalidation counts accept both {l1,l2} and legacy {invalidated:{l1,l2}}', async () => {
    post.mockResolvedValueOnce({ data: { l1: 1, l2: 0, node: 'n1' } })
    const a = await invalidateNegCacheKey({ scope: 's', repoType: 'npm', artifactName: 'a', artifactVersion: '' })
    expect(a).toEqual({ l1: 1, l2: 0, node: 'n1' })
    expect(post).toHaveBeenCalledWith('/admin/neg-cache/invalidate', {
      scope: 's', repoType: 'npm', artifactName: 'a', version: '',
    })

    post.mockResolvedValueOnce({ data: { invalidated: { l1: 3, l2: 4 } } })
    const b = await invalidateNegCachePackage({ artifactName: 'a' })
    expect(b).toEqual({ l1: 3, l2: 4, node: undefined })
  })

  it('inspect and refresh omit repo when not given', async () => {
    get.mockResolvedValue({ data: {} })
    post.mockResolvedValue({ data: {} })
    await inspectCooldownPackage({ repoType: 'npm', package: 'lodash' })
    expect(get.mock.calls[0][0]).toBe('/cooldown/inspect')
    expect(get.mock.calls[0][1].params).toEqual({ repoType: 'npm', package: 'lodash' })
    await refreshCooldownPackage({ repoType: 'pypi', package: 'requests', repo: 'pypi-proxy' })
    expect(post.mock.calls[0][0]).toBe('/cooldown/refresh-package')
    expect(post.mock.calls[0][1]).toEqual({ repoType: 'pypi', package: 'requests', repo: 'pypi-proxy' })
  })

  it('troubleshootUrl hits the admin endpoint', async () => {
    get.mockResolvedValue({ data: {} })
    await troubleshootUrl('https://r.example.com/npm/lodash')
    expect(get.mock.calls[0][0]).toBe('/admin/troubleshoot')
    expect(get.mock.calls[0][1].params).toEqual({ url: 'https://r.example.com/npm/lodash' })
  })

  it('fixPath strips the /api/v1 prefix and refuses absolute URLs', async () => {
    expect(fixPath('/api/v1/admin/neg-cache/invalidate-package')).toBe('/admin/neg-cache/invalidate-package')
    expect(fixPath('/cooldown/refresh-package')).toBe('/cooldown/refresh-package')
    expect(fixPath('admin/x')).toBe('/admin/x')
    expect(() => fixPath('https://evil.example.com/x')).toThrow()
    expect(() => fixPath('//evil.example.com/x')).toThrow()

    post.mockResolvedValue({ data: { ok: true } })
    await runTroubleshootFix({ action: 'refresh-package', endpoint: '/api/v1/cooldown/refresh-package', body: { a: 1 } })
    expect(post.mock.calls[0][0]).toBe('/cooldown/refresh-package')
    expect(post.mock.calls[0][1]).toEqual({ a: 1 })
  })
})
