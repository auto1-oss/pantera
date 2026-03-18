import { describe, it, expect, vi } from 'vitest'
import { usePagination } from '../usePagination'

describe('usePagination', () => {
  it('starts at page 0 with default size', () => {
    const mockFn = vi.fn().mockResolvedValue({ items: [], page: 0, size: 20, total: 0, hasMore: false })
    const { page, size } = usePagination(mockFn)
    expect(page.value).toBe(0)
    expect(size.value).toBe(20)
  })

  it('fetches data and updates state', async () => {
    const mockFn = vi.fn().mockResolvedValue({
      items: [{ name: 'a' }], page: 0, size: 20, total: 1, hasMore: false,
    })
    const { items, total, fetch } = usePagination(mockFn)
    await fetch()
    expect(items.value).toEqual([{ name: 'a' }])
    expect(total.value).toBe(1)
    expect(mockFn).toHaveBeenCalledWith({ page: 0, size: 20 })
  })

  it('advances to next page', async () => {
    const mockFn = vi.fn().mockResolvedValue({
      items: [{ name: 'b' }], page: 1, size: 20, total: 40, hasMore: true,
    })
    const { page, nextPage } = usePagination(mockFn)
    page.value = 0
    await nextPage()
    expect(page.value).toBe(1)
  })
})
