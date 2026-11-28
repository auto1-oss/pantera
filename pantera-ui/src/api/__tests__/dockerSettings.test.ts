import { describe, it, expect, vi, beforeEach } from 'vitest'

// Stub the axios client: the wrappers are thin, so what matters is the URL
// and the body each one sends.
const get = vi.fn()
const put = vi.fn()

vi.mock('../client', () => ({
  getApiClient: () => ({ get, put }),
}))

import { getDockerSettings, updateDockerSettings } from '../auth'

beforeEach(() => {
  get.mockReset(); put.mockReset()
})

describe('docker settings API', () => {
  it('reads the tag refresh age as a number', async () => {
    get.mockResolvedValue({ data: { tag_refresh_seconds: 3600 } })

    await expect(getDockerSettings()).resolves.toEqual({ tag_refresh_seconds: 3600 })
    expect(get).toHaveBeenCalledWith('/admin/docker-settings')
  })

  it('writes the tag refresh age as a number', async () => {
    put.mockResolvedValue({ status: 204 })

    await updateDockerSettings({ tag_refresh_seconds: 0 })

    expect(put).toHaveBeenCalledWith('/admin/docker-settings', { tag_refresh_seconds: 0 })
  })
})
