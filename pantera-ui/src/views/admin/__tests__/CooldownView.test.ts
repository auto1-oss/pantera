import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import CooldownView from '../CooldownView.vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'

// The settings API is mocked at module level so we can observe which
// params CooldownView forwards to /cooldown/blocked for each
// interaction (filter change, pagination reset, etc.).
const getCooldownOverviewMock = vi.fn()
const getCooldownBlockedMock = vi.fn()
const getCooldownHistoryMock = vi.fn()

vi.mock('@/api/settings', () => ({
  getCooldownOverview: (...args: unknown[]) => getCooldownOverviewMock(...args),
  getCooldownBlocked: (...args: unknown[]) => getCooldownBlockedMock(...args),
  getCooldownHistory: (...args: unknown[]) => getCooldownHistoryMock(...args),
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

// Seed the auth store with the given permission map so `auth.hasAction`
// returns the expected booleans without going through the real login
// flow. Must be called BEFORE mountView() because CooldownView reads
// `canWrite` synchronously on setup.
function seedAuth(permissions: Record<string, string[]>) {
  const auth = useAuthStore()
  auth.user = {
    name: 'tester',
    context: 'ci',
    permissions,
  } as unknown as typeof auth.user
}

describe('CooldownView filter dropdowns', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getCooldownOverviewMock.mockReset()
    getCooldownBlockedMock.mockReset()
    getCooldownHistoryMock.mockReset()
    getCooldownOverviewMock.mockResolvedValue([
      { name: 'npm-proxy', type: 'npm-proxy', cooldown: '7d', active_blocks: 1 },
      { name: 'pypi-proxy', type: 'pypi-proxy', cooldown: '7d', active_blocks: 0 },
    ])
    getCooldownBlockedMock.mockResolvedValue({
      items: [], page: 0, size: 50, total: 0, hasMore: false,
    })
    getCooldownHistoryMock.mockResolvedValue({
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

  it('hides the history toggle when user lacks history permission', async () => {
    // Seed auth with no history permission. Even if the user has
    // api_cooldown_permissions.read, the SelectButton must stay hidden
    // because canReadHistory checks api_cooldown_history_permissions.read.
    seedAuth({ api_cooldown_permissions: ['read'] })
    const wrapper = mountView()
    await flushPromises()

    const toggle = wrapper.find('[aria-label="Toggle active vs. history view"]')
    expect(toggle.exists()).toBe(false)
  })

  it('shows the history toggle for users with history permission', async () => {
    seedAuth({ api_cooldown_history_permissions: ['read'] })
    const wrapper = mountView()
    await flushPromises()

    const toggle = wrapper.find('[aria-label="Toggle active vs. history view"]')
    expect(toggle.exists()).toBe(true)
  })

  it('shows history rows when toggle is set to history', async () => {
    seedAuth({
      api_cooldown_permissions: ['read', 'write'],
      api_cooldown_history_permissions: ['read'],
    })
    getCooldownHistoryMock.mockResolvedValue({
      items: [
        {
          package_name: 'left-pad',
          version: '1.2.3',
          repo: 'npm-proxy',
          repo_type: 'npm-proxy',
          reason: 'CVE-2024-1234',
          blocked_date: '2026-04-10T00:00:00Z',
          blocked_until: '2026-04-17T00:00:00Z',
          remaining_hours: 0,
          archived_at: '2026-04-17T01:00:00Z',
          archive_reason: 'EXPIRED',
          archived_by: 'system',
        },
      ],
      page: 0, size: 50, total: 1, hasMore: false,
    })

    const wrapper = mountView()
    await flushPromises()

    const vm = wrapper.vm as unknown as { mode: 'active' | 'history' }
    vm.mode = 'history'
    await flushPromises()

    expect(getCooldownHistoryMock).toHaveBeenCalled()
    const text = wrapper.text()
    expect(text).toContain('EXPIRED')
    expect(text).toContain('left-pad')
  })

  it('hides unblock column in history mode', async () => {
    seedAuth({
      api_cooldown_permissions: ['read', 'write'],
      api_cooldown_history_permissions: ['read'],
    })
    getCooldownHistoryMock.mockResolvedValue({
      items: [
        {
          package_name: 'left-pad',
          version: '1.2.3',
          repo: 'npm-proxy',
          repo_type: 'npm-proxy',
          reason: 'CVE',
          blocked_date: '2026-04-10T00:00:00Z',
          blocked_until: '2026-04-17T00:00:00Z',
          remaining_hours: 0,
          archived_at: '2026-04-17T01:00:00Z',
          archive_reason: 'EXPIRED',
          archived_by: 'system',
        },
      ],
      page: 0, size: 50, total: 1, hasMore: false,
    })

    const wrapper = mountView()
    await flushPromises()

    // In active mode, the Actions column exists (canWrite is true).
    expect(wrapper.text()).toContain('Actions')

    const vm = wrapper.vm as unknown as { mode: 'active' | 'history' }
    vm.mode = 'history'
    await flushPromises()

    // History mode: no Actions header, no unblock button.
    expect(wrapper.text()).not.toContain('Actions')
    expect(wrapper.find('button .pi-unlock').exists()).toBe(false)
  })
})
