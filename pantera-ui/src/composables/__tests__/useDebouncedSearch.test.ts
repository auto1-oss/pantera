import { describe, it, expect, vi, afterEach } from 'vitest'
import { nextTick } from 'vue'
import { useDebouncedSearch } from '../useDebouncedSearch'

describe('useDebouncedSearch', () => {
  afterEach(() => { vi.useRealTimers() })

  it('debounces typing into one fetch and aborts the superseded run', async () => {
    vi.useFakeTimers()
    const signals: AbortSignal[] = []
    const fetch = vi.fn(async (signal: AbortSignal) => { signals.push(signal) })
    const s = useDebouncedSearch(fetch, { delayMs: 300 })
    s.query.value = 'm'
    await nextTick()
    s.query.value = 'ma'
    await nextTick()
    expect(fetch).not.toHaveBeenCalled()
    vi.advanceTimersByTime(300)
    expect(fetch).toHaveBeenCalledTimes(1)
    await s.run()
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(signals[0].aborted).toBe(true)
    expect(signals[1].aborted).toBe(false)
    s.dispose()
  })

  it('run() clears a pending debounce so the fetch happens once', async () => {
    vi.useFakeTimers()
    const fetch = vi.fn(async () => {})
    const s = useDebouncedSearch(fetch, { delayMs: 300 })
    s.query.value = 'x'
    await nextTick()
    await s.run()
    vi.advanceTimersByTime(1000)
    expect(fetch).toHaveBeenCalledTimes(1)
    s.dispose()
  })

  it('an aborted fetch does not clear loading for the newer run', async () => {
    let resolveFirst: (() => void) | null = null
    const fetch = vi.fn((signal: AbortSignal) => new Promise<void>((resolve) => {
      if (fetch.mock.calls.length === 1) resolveFirst = resolve
      else resolve()
      void signal
    }))
    const s = useDebouncedSearch(fetch)
    const first = s.run()
    const second = s.run()
    await second
    resolveFirst!()
    await first
    expect(s.loading.value).toBe(false)
    s.dispose()
  })
})
