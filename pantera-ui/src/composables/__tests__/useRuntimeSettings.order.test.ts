import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useRuntimeSettings } from '../useRuntimeSettings'

const patchMock = vi.fn()

vi.mock('@/api/runtimeSettings', () => ({
  listRuntimeSettings: () => Promise.resolve([
    { key: 'http_client.bulkhead.min_permits', value: 5, default: 5, source: 'default' },
    { key: 'http_client.bulkhead.max_permits', value: 100, default: 100, source: 'default' },
    { key: 'http_client.bulkhead.initial_permits', value: 10, default: 10, source: 'default' },
  ]),
  patchRuntimeSetting: (...args: unknown[]) => patchMock(...args),
  resetRuntimeSetting: vi.fn(),
}))

const MIN = 'http_client.bulkhead.min_permits' as const
const MAX = 'http_client.bulkhead.max_permits' as const
const INITIAL = 'http_client.bulkhead.initial_permits' as const

// The server refuses any single change that leaves min <= initial <= max
// broken, so a multi-key save must keep that invariant after every step.
describe('useRuntimeSettings — permit save order', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    patchMock.mockReset()
    patchMock.mockImplementation((key: string, value: number) =>
      Promise.resolve({ key, value, default: value, source: 'db' }),
    )
  })

  it('raises max before initial before min', async () => {
    const runtime = useRuntimeSettings()
    await runtime.load()
    runtime.edited[MIN] = 200
    runtime.edited[INITIAL] = 300
    runtime.edited[MAX] = 500
    expect(await runtime.saveAllDirty()).toBe(true)
    expect(patchMock.mock.calls.map(call => call[0])).toEqual([MAX, INITIAL, MIN])
  })

  it('lowers min before initial before max', async () => {
    const runtime = useRuntimeSettings()
    await runtime.load()
    runtime.edited[MIN] = 1
    runtime.edited[INITIAL] = 2
    runtime.edited[MAX] = 3
    expect(await runtime.saveAllDirty()).toBe(true)
    expect(patchMock.mock.calls.map(call => call[0])).toEqual([MIN, INITIAL, MAX])
  })

  it('does not send an inconsistent combination', async () => {
    const runtime = useRuntimeSettings()
    await runtime.load()
    runtime.edited[MAX] = 3
    expect(await runtime.saveAllDirty()).toBe(false)
    expect(patchMock).not.toHaveBeenCalled()
    expect(runtime.anyDirty.value).toBe(true)
  })
})
