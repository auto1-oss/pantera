import { describe, it, expect, vi, beforeEach } from 'vitest'

const del = vi.fn()

vi.mock('../client', () => ({
  getApiClient: () => ({ delete: del }),
}))

import { deleteRepo } from '../repos'

describe('deleteRepo', () => {
  beforeEach(() => {
    del.mockReset()
  })

  it('reports a finished delete on 200', async () => {
    del.mockResolvedValue({ status: 200, data: '' })
    await expect(deleteRepo('old')).resolves.toBe('deleted')
    expect(del).toHaveBeenCalledWith('/repositories/old')
  })

  it('reports a delete still running on 202', async () => {
    del.mockResolvedValue({ status: 202, data: { status: 'deleting' } })
    await expect(deleteRepo('big')).resolves.toBe('deleting')
  })
})
