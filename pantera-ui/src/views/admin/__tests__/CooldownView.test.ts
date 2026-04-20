import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import CooldownView from '../CooldownView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primevue/themes/aura'

// The settings API is mocked at module level so we can observe which
// params CooldownView forwards to /cooldown/blocked for each
// interaction (filter change, pagination reset, etc.).
const getCooldownOverviewMock = vi.fn()
const getCooldownBlockedMock = vi.fn()

vi.mock('@/api/settings', () => ({
  getCooldownOverview: (...args: unknown[]) => getCooldownOverviewMock(...args),
  getCooldownBlocked: (...args: unknown[]) => getCooldownBlockedMock(...args),
}))

vi.mock('@/api/repos', () => ({
  unblockArtifact: vi.fn().mockResolvedValue(undefined),
  unblockAll: vi.fn().mockResolvedValue(undefined),
}))

// Stub AppLayout so we don't pull in AppHeader's transitive asset
// imports (e.g. /pantera-128.png) which vite cannot resolve in the
// test environment. We only care about CooldownView's own logic.
vi.mock('@/components/layout/AppLayout.vue', () => ({
  default: { name: 'AppLayoutStub', template: '<div><slot /></div>' },
}))

function mountView() {
  return mount(CooldownView, {
    global: {
      plugins: [[PrimeVue, { theme: { preset: Aura } }]],
      directives: {
        // PrimeVue's tooltip directive isn't registered in tests; a
        // no-op stub keeps the "Failed to resolve directive" warning
        // out of the output.
        tooltip: {},
      },
      stubs: {
        'router-link': true,
        'router-view': true,
        AppLayout: { template: '<div><slot /></div>' },
      },
    },
  })
}

describe('CooldownView filter dropdowns', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getCooldownOverviewMock.mockReset()
    getCooldownBlockedMock.mockReset()
    getCooldownOverviewMock.mockResolvedValue([
      { name: 'npm-proxy', type: 'npm-proxy', cooldown: '7d', active_blocks: 1 },
      { name: 'pypi-proxy', type: 'pypi-proxy', cooldown: '7d', active_blocks: 0 },
    ])
    getCooldownBlockedMock.mockResolvedValue({
      items: [], page: 0, size: 50, total: 0, hasMore: false,
    })
  })

  it('reloads with repo filter when dropdown changes', async () => {
    const wrapper = mountView()
    await flushPromises()

    // Initial load: no filters
    expect(getCooldownBlockedMock).toHaveBeenCalled()
    const initialParams = getCooldownBlockedMock.mock.calls[0][0]
    expect(initialParams.repo).toBeUndefined()

    const vm = wrapper.vm as unknown as {
      repoFilter: string | null
    }
    vm.repoFilter = 'npm-proxy'
    await flushPromises()

    const lastCall = getCooldownBlockedMock.mock.calls.at(-1)!
    expect(lastCall[0].repo).toBe('npm-proxy')
  })

  it('reloads with repo_type filter when type dropdown changes', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      typeFilter: string | null
    }
    vm.typeFilter = 'docker'
    await flushPromises()

    const lastCall = getCooldownBlockedMock.mock.calls.at(-1)!
    expect(lastCall[0].repo_type).toBe('docker')
  })

  it('resets pagination on filter change', async () => {
    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as {
      blockedPage: number
      repoFilter: string | null
    }
    vm.blockedPage = 2
    vm.repoFilter = 'pypi-proxy'
    await flushPromises()

    const lastCall = getCooldownBlockedMock.mock.calls.at(-1)!
    expect(lastCall[0].page).toBe(0)
    expect(lastCall[0].repo).toBe('pypi-proxy')
  })
})
