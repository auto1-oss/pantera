import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useRuntimeSettings } from '../useRuntimeSettings'
import { useNotificationStore } from '@/stores/notifications'

const patchMock = vi.fn()

vi.mock('@/api/runtimeSettings', () => ({
  listRuntimeSettings: () => Promise.resolve([
    { key: 'http_client.bulkhead.max_permits', value: 10, default: 10, source: 'default' },
  ]),
  patchRuntimeSetting: (...args: unknown[]) => patchMock(...args),
  resetRuntimeSetting: vi.fn(),
}))

const KEY = 'http_client.bulkhead.max_permits' as const

describe('useRuntimeSettings — rejected save', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    patchMock.mockReset()
  })

  it('keeps a rejected edit so the bulkhead section stays dirty', async () => {
    patchMock.mockRejectedValue({
      response: { status: 400, data: { message: 'max_permits must be >= min_permits' } },
    })
    const runtime = useRuntimeSettings()
    await runtime.load()
    runtime.edited[KEY] = 0
    const saved = await runtime.saveAllDirty()
    expect(saved, 'saveAllDirty must report the rejection').toBe(false)
    expect(runtime.edited[KEY], 'the rejected edit must be kept').toBe(0)
    expect(runtime.anyDirty.value, 'the section must stay dirty so its save-bar chip stays').toBe(true)
    expect(
      useNotificationStore().toasts.some(
        t => t.severity === 'error' && t.detail === 'max_permits must be >= min_permits',
      ),
      'the error toast must carry the server message',
    ).toBe(true)
  })
})
